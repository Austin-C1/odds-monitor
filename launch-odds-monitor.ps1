$rootDir = (Resolve-Path (Split-Path -Parent $MyInvocation.MyCommand.Path)).Path
$backendScript = Join-Path $rootDir 'start-odds-backend.ps1'
$backendDir = Join-Path $rootDir 'backend'
$frontendDir = Join-Path $rootDir 'frontend'
$frontendUrl = 'http://127.0.0.1:18881'
$frontendLoginUrl = "$frontendUrl/login"
$frontendApiReadyUrl = "$frontendUrl/api/auth/check-first-use"
$databasePort = 13308
$databaseContainerName = 'odds-monitor-mysql'
$databaseImage = 'mysql:8.1'
$databaseVolumeName = 'odds-monitor-mysql-data'
$databaseName = 'odds_monitor'
$databasePassword = 'change-me'
$dockerDesktopExe = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
$backendPort = 18000
$backendUrl = "http://127.0.0.1:$backendPort"
$backendReadyUrl = "$backendUrl/api/auth/check-first-use"
$frontendPort = 18881
$backendStartupTimeoutSeconds = 180
$logDir = Join-Path $rootDir 'logs'
$frontendOutLog = Join-Path $logDir 'frontend-live.out.log'
$frontendErrLog = Join-Path $logDir 'frontend-live.err.log'
$frontendDistDir = Join-Path $frontendDir 'dist'
$frontendDistMarker = Join-Path $frontendDistDir '.desktop-runtime.json'
$frontendStaticServerScript = Join-Path $rootDir 'scripts\serve-odds-frontend.ps1'
$powershellExe = Join-Path $PSHOME 'powershell.exe'
$localConfig = Join-Path $rootDir 'config\local.env.ps1'

if (Test-Path $localConfig) {
    . $localConfig
}

function Write-Status {
    param([string]$Message)
    Write-Host "[OddsMonitor] $Message"
}

function Fail-Launch {
    param([string]$Message)

    Write-Host ''
    Write-Host "[OddsMonitor] Launch failed: $Message" -ForegroundColor Red
    Write-Host "[OddsMonitor] Logs: $logDir"
    Write-Host ''
    if ([System.Environment]::UserInteractive -and -not [System.Console]::IsInputRedirected) {
        Write-Host 'Press any key to close...'
        try {
            [void][System.Console]::ReadKey($true)
        }
        catch {
        }
    }
    exit 1
}

function Invoke-LaunchStep {
    param(
        [string]$Message,
        [scriptblock]$Action
    )

    Write-Status $Message
    try {
        & $Action
    }
    catch {
        Fail-Launch $_.Exception.Message
    }
}

function Set-TrimmedEnv {
    param([string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ($null -ne $value) {
        [Environment]::SetEnvironmentVariable($Name, $value.Trim(), 'Process')
    }
}

function Get-TrimmedString {
    param([object]$Value)

    if ($null -eq $Value) {
        return $null
    }

    return ([string]$Value).Trim()
}

$databaseContainerName = Get-TrimmedString $databaseContainerName
$databaseImage = Get-TrimmedString $databaseImage
$databaseVolumeName = Get-TrimmedString $databaseVolumeName
$databaseName = Get-TrimmedString $databaseName
$databasePassword = ([string]$databasePassword).Trim()
@('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'ENCRYPTION_KEY', 'ADMIN_RESET_PASSWORD_KEY') |
    ForEach-Object { Set-TrimmedEnv -Name $_ }

function Test-PortListening {
    param([int]$Port)

    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Wait-PortListening {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening -Port $Port) {
            return $true
        }
        Start-Sleep -Seconds 1
    }

    return $false
}

function Wait-PortFree {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 20
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-PortListening -Port $Port)) {
            return $true
        }
        Start-Sleep -Seconds 1
    }

    return -not (Test-PortListening -Port $Port)
}

function Wait-HttpReady {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                return $true
            }
        }
        catch {
        }

        Start-Sleep -Seconds 1
    }

    return $false
}

function Test-PostReady {
    param([string]$Url)

    try {
        $response = Invoke-WebRequest `
            -Uri $Url `
            -Method Post `
            -Body '{}' `
            -ContentType 'application/json' `
            -UseBasicParsing `
            -TimeoutSec 5
        return $response.StatusCode -eq 200
    }
    catch {
        return $false
    }
}

function Wait-PostReady {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PostReady -Url $Url) {
            return $true
        }
        Start-Sleep -Seconds 1
    }

    return $false
}

function Stop-OddsMonitorFrontendServer {
    $processes = Get-CimInstance Win32_Process |
        Where-Object { $_.CommandLine -like '*serve-odds-frontend.ps1*' }

    foreach ($process in $processes) {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
    }

    if ($processes) {
        Start-Sleep -Seconds 2
    }
}

function Stop-OddsMonitorBackendServer {
    $processes = Get-CimInstance Win32_Process |
        Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*odds-monitor-backend*' }

    foreach ($process in $processes) {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
    }

    if ($processes) {
        Start-Sleep -Seconds 3
    }
}

function Test-CommandLineContainsPath {
    param(
        [string]$CommandLine,
        [string]$Path
    )

    if ([string]::IsNullOrWhiteSpace($CommandLine) -or [string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }

    $normalizedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    return $CommandLine.IndexOf($normalizedPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Test-FrontendRuntimeCurrent {
    $processes = Get-CimInstance Win32_Process |
        Where-Object {
            $_.CommandLine -like '*serve-odds-frontend.ps1*' `
                -and $_.CommandLine -like "*-Port $frontendPort*"
        }

    if (-not $processes) {
        return $false
    }

    foreach ($process in $processes) {
        if (-not (Test-CommandLineContainsPath -CommandLine $process.CommandLine -Path $frontendDistDir)) {
            return $false
        }
        if (-not (Test-CommandLineContainsPath -CommandLine $process.CommandLine -Path $frontendStaticServerScript)) {
            return $false
        }
        if ($process.CommandLine -notlike "*-BackendUrl $backendUrl*") {
            return $false
        }
    }

    return $true
}

function Get-NewestWriteTime {
    param([string[]]$Paths)

    $latest = Get-Date '2000-01-01'
    foreach ($path in $Paths) {
        if (-not (Test-Path $path)) {
            continue
        }

        $item = Get-Item $path
        if ($item.PSIsContainer) {
            $candidate = Get-ChildItem -Path $item.FullName -Recurse -File -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if ($candidate -and $candidate.LastWriteTime -gt $latest) {
                $latest = $candidate.LastWriteTime
            }
            continue
        }

        if ($item.LastWriteTime -gt $latest) {
            $latest = $item.LastWriteTime
        }
    }

    return $latest
}

function Test-DesktopFrontendBuildAvailable {
    param([int]$BackendPort)

    if (
        -not (Test-Path $frontendDistDir) `
        -or -not (Test-Path $frontendStaticServerScript) `
        -or -not (Test-Path (Join-Path $frontendDistDir 'index.html'))
    ) {
        return $false
    }

    if (Test-Path $frontendDistMarker) {
        try {
            $marker = Get-Content -Path $frontendDistMarker -Raw | ConvertFrom-Json
            if ($marker.apiUrl -ne "http://127.0.0.1:$BackendPort" -or $marker.wsUrl -ne "ws://127.0.0.1:$BackendPort") {
                return $false
            }
        }
        catch {
            return $false
        }
    }

    $sourceLatest = Get-NewestWriteTime -Paths @(
        (Join-Path $frontendDir 'src'),
        (Join-Path $frontendDir 'public'),
        (Join-Path $frontendDir 'index.html'),
        (Join-Path $frontendDir 'package.json'),
        (Join-Path $frontendDir 'package-lock.json'),
        (Join-Path $frontendDir 'vite.config.ts')
    )
    $buildLatest = Get-NewestWriteTime -Paths @($frontendDistDir)

    return $buildLatest -ge $sourceLatest
}

function Get-BackendJarVersion {
    param([System.IO.FileInfo]$JarFile)

    $match = [regex]::Match($JarFile.BaseName, '^odds-monitor-backend-(?<version>\d+(?:\.\d+)*)(?:[-+].*)?$')
    if (-not $match.Success) {
        return [version]'0.0.0.0'
    }

    $parts = @($match.Groups['version'].Value.Split('.'))
    while ($parts.Count -lt 4) {
        $parts += '0'
    }
    return [version]($parts[0..3] -join '.')
}

function Sort-BackendJarCandidates {
    param([System.IO.FileInfo[]]$JarFiles)

    return $JarFiles |
        Sort-Object `
            @{ Expression = { Get-BackendJarVersion -JarFile $_ }; Descending = $true },
            @{ Expression = { $_.LastWriteTimeUtc }; Descending = $true }
}

function Get-CurrentBackendJar {
    $backendLibDir = Join-Path $backendDir 'build\libs'
    if (-not (Test-Path $backendLibDir)) {
        return $null
    }

    return Sort-BackendJarCandidates -JarFiles @(
        Get-ChildItem -Path $backendLibDir -Filter 'odds-monitor-backend-*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*-plain.jar' }
    ) | Select-Object -First 1
}

function Test-BackendBuildCurrent {
    $jarFile = Get-CurrentBackendJar

    if (-not $jarFile) {
        return $false
    }

    $sourceLatest = Get-NewestWriteTime -Paths @(
        (Join-Path $backendDir 'src'),
        (Join-Path $backendDir 'build.gradle.kts'),
        (Join-Path $backendDir 'settings.gradle.kts'),
        (Join-Path $backendDir 'gradle.properties')
    )

    return $jarFile.LastWriteTime -ge $sourceLatest
}

function Test-BackendRuntimeCurrent {
    $jarFile = Get-CurrentBackendJar
    if (-not $jarFile) {
        return $false
    }

    $processes = Get-CimInstance Win32_Process |
        Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*odds-monitor-backend*' }

    foreach ($process in $processes) {
        if ($process.CommandLine -notlike "*$($jarFile.Name)*") {
            return $false
        }
        if ($process.CreationDate -and $process.CreationDate -lt $jarFile.LastWriteTime) {
            return $false
        }
    }

    return $true
}

function Test-DockerAvailable {
    try {
        docker version --format '{{.Server.Version}}' 1>$null 2>$null
        return $LASTEXITCODE -eq 0
    }
    catch {
        return $false
    }
}

function Wait-DockerAvailable {
    param([int]$TimeoutSeconds = 120)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-DockerAvailable) {
            return $true
        }
        Start-Sleep -Seconds 5
    }

    return $false
}

function Ensure-DatabaseContainer {
    param(
        [string]$ContainerName,
        [int]$Port,
        [string]$Image,
        [string]$RootPassword,
        [string]$DatabaseName,
        [string]$VolumeName
    )

    $databaseContainerExists = docker ps -a --filter "name=^/${ContainerName}$" --format "{{.Names}}"
    if ($databaseContainerExists -contains $ContainerName) {
        $databaseContainerRunning = docker ps --filter "name=^/${ContainerName}$" --format "{{.Names}}"
        if (-not ($databaseContainerRunning -contains $ContainerName)) {
            docker start $ContainerName | Out-Null
        }
        return
    }

    docker run -d `
        --name $ContainerName `
        --restart unless-stopped `
        -p "${Port}:3306" `
        -e "TZ=Asia/Shanghai" `
        -e "MYSQL_ROOT_PASSWORD=$RootPassword" `
        -e "MYSQL_DATABASE=$DatabaseName" `
        -v "${VolumeName}:/var/lib/mysql" `
        $Image `
        --character-set-server=utf8mb4 `
        --collation-server=utf8mb4_unicode_ci | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "Database container creation failed: $ContainerName"
    }
}

function Wait-DatabaseReady {
    param(
        [string]$ContainerName,
        [string]$RootPassword,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            docker exec $ContainerName sh -lc "mysqladmin ping -h 127.0.0.1 -p$RootPassword --silent" 1>$null 2>$null
            if ($LASTEXITCODE -eq 0) {
                return $true
            }
        }
        catch {
        }

        Start-Sleep -Seconds 2
    }

    return $false
}

Invoke-LaunchStep 'Checking program files' {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null

    if (-not (Test-Path $backendScript)) {
        throw "Backend start script not found: $backendScript"
    }

    if (-not (Test-Path $frontendDir)) {
        throw "Frontend directory not found: $frontendDir"
    }
}

$frontendMode = if (Test-DesktopFrontendBuildAvailable -BackendPort $backendPort) { 'static' } else { 'dev' }

Invoke-LaunchStep 'Checking database' {
    if (-not (Test-PortListening -Port $databasePort)) {
        if (-not (Test-DockerAvailable)) {
            if (-not (Test-Path $dockerDesktopExe)) {
                throw "Docker Desktop not found: $dockerDesktopExe"
            }

            Write-Status 'Starting Docker Desktop'
            Start-Process -FilePath $dockerDesktopExe | Out-Null
        }

        if (-not (Wait-DockerAvailable -TimeoutSeconds 180)) {
            throw 'Docker did not become available.'
        }

        Ensure-DatabaseContainer `
            -ContainerName $databaseContainerName `
            -Port $databasePort `
            -Image $databaseImage `
            -RootPassword $databasePassword `
            -DatabaseName $databaseName `
            -VolumeName $databaseVolumeName

        if (-not (Wait-PortListening -Port $databasePort -TimeoutSeconds 90)) {
            throw "Database did not start on port $databasePort."
        }

        if (-not (Wait-DatabaseReady -ContainerName $databaseContainerName -RootPassword $databasePassword -TimeoutSeconds 120)) {
            throw "Database did not become ready inside container $databaseContainerName."
        }
    }
}

Invoke-LaunchStep 'Checking backend service' {
    if ((Test-PortListening -Port $backendPort) -and ((-not (Test-BackendBuildCurrent)) -or (-not (Test-BackendRuntimeCurrent)))) {
        Write-Status 'Backend package is outdated; restarting backend'
        Stop-OddsMonitorBackendServer
        if (-not (Wait-PortFree -Port $backendPort -TimeoutSeconds 20)) {
            throw "Backend port $backendPort is still occupied."
        }
    }

    if ((Test-PortListening -Port $backendPort) -and -not (Test-PostReady -Url $backendReadyUrl)) {
        Write-Status 'Backend port is occupied but unhealthy; restarting backend'
        Stop-OddsMonitorBackendServer
        if (-not (Wait-PortFree -Port $backendPort -TimeoutSeconds 20)) {
            throw "Backend port $backendPort is still occupied."
        }
    }

    if (-not (Test-PortListening -Port $backendPort)) {
        Start-Process -FilePath $powershellExe `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $backendScript) `
            -WorkingDirectory $rootDir `
            -WindowStyle Hidden | Out-Null
    }

    if (-not (Wait-PostReady -Url $backendReadyUrl -TimeoutSeconds $backendStartupTimeoutSeconds)) {
        throw "Backend did not become ready at $backendReadyUrl."
    }
}

Invoke-LaunchStep 'Checking frontend service' {
    if (($frontendMode -eq 'static') -and (Test-PortListening -Port $frontendPort) -and -not (Test-FrontendRuntimeCurrent)) {
        Write-Status 'Frontend service belongs to a different install or stale dist; restarting frontend'
        Stop-OddsMonitorFrontendServer
        if (-not (Wait-PortFree -Port $frontendPort -TimeoutSeconds 20)) {
            throw "Frontend port $frontendPort is still occupied."
        }
    }

    if ((Test-PortListening -Port $frontendPort) -and -not (Test-PostReady -Url $frontendApiReadyUrl)) {
        Write-Status 'Frontend service is outdated or API proxy is unhealthy; restarting frontend'
        Stop-OddsMonitorFrontendServer
        if (-not (Wait-PortFree -Port $frontendPort -TimeoutSeconds 20)) {
            throw "Frontend port $frontendPort is still occupied."
        }
    }

    if (-not (Test-PortListening -Port $frontendPort)) {
        if ($frontendMode -eq 'static') {
            Start-Process -FilePath $powershellExe `
                -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $frontendStaticServerScript, '-Root', $frontendDistDir, '-ListenHost', '127.0.0.1', '-Port', $frontendPort.ToString(), '-BackendUrl', $backendUrl) `
                -WorkingDirectory $rootDir `
                -RedirectStandardOutput $frontendOutLog `
                -RedirectStandardError $frontendErrLog `
                -WindowStyle Hidden | Out-Null
        }
        else {
            $npmCmd = (Get-Command 'npm.cmd' -ErrorAction Stop).Source
            Start-Process -FilePath $npmCmd `
                -ArgumentList @('run', 'dev', '--', '--host', '127.0.0.1', '--port', '18881') `
                -WorkingDirectory $frontendDir `
                -RedirectStandardOutput $frontendOutLog `
                -RedirectStandardError $frontendErrLog `
                -WindowStyle Hidden | Out-Null
        }
    }

    if (-not (Wait-PortListening -Port $frontendPort -TimeoutSeconds 60)) {
        throw "Frontend did not start on port $frontendPort."
    }

    if (-not (Wait-HttpReady -Url $frontendLoginUrl -TimeoutSeconds 60)) {
        throw "Frontend page did not become available at $frontendLoginUrl."
    }

    if (-not (Test-PostReady -Url $frontendApiReadyUrl)) {
        throw "Frontend API proxy did not become ready at $frontendApiReadyUrl."
    }
}

Write-Status 'Opening login page'
Start-Process $frontendLoginUrl | Out-Null
Write-Status 'Ready'




