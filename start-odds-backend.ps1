$rootDir = (Resolve-Path (Split-Path -Parent $MyInvocation.MyCommand.Path)).Path
$backendDir = Join-Path $rootDir 'backend'
$javaExe = Join-Path $rootDir '.tools\jdk-17.0.18+8\bin\java.exe'

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
        Get-ChildItem -Path $backendLibDir -Filter 'odds-monitor-backend-*.jar' -File |
            Where-Object { $_.Name -notlike '*-plain.jar' }
    ) | Select-Object -First 1
}
$jarFile = Get-CurrentBackendJar
$jarPath = if ($jarFile) { $jarFile.FullName } else { Join-Path $backendDir 'build\libs\odds-monitor-backend.jar' }
$outLog = Join-Path $rootDir 'backend-live.out.log'
$errLog = Join-Path $rootDir 'backend-live.err.log'
$localConfig = Join-Path $rootDir 'config\local.env.ps1'
$sourcePaths = @(
    (Join-Path $backendDir 'src'),
    (Join-Path $backendDir 'build.gradle.kts'),
    (Join-Path $backendDir 'settings.gradle.kts'),
    (Join-Path $backendDir 'gradle.properties')
)

$env:DB_URL = if ($env:DB_URL) { $env:DB_URL } else { 'jdbc:mysql://127.0.0.1:13308/odds_monitor?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true' }
$env:DB_USERNAME = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' }
$env:DB_PASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'change-me' }
$env:JWT_SECRET = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { 'change-me-change-me-change-me-change-me' }
$env:ENCRYPTION_KEY = if ($env:ENCRYPTION_KEY) { $env:ENCRYPTION_KEY } else { 'change-me-change-me-change-me-change-me' }
$env:ADMIN_RESET_PASSWORD_KEY = if ($env:ADMIN_RESET_PASSWORD_KEY) { $env:ADMIN_RESET_PASSWORD_KEY } else { 'change-me' }
$env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_ENABLED = if ($env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_ENABLED) { $env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_ENABLED } else { 'true' }
$env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_USERNAME = if ($env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_USERNAME) { $env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_USERNAME } else { '123456' }
$env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_PASSWORD = if ($env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_PASSWORD) { $env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_PASSWORD } else { '123456' }
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:SERVER_PORT = '18000'

if (Test-Path $localConfig) {
    . $localConfig
}

function Set-TrimmedEnv {
    param([string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ($null -ne $value) {
        [Environment]::SetEnvironmentVariable($Name, $value.Trim(), 'Process')
    }
}

@(
    'DB_URL',
    'DB_USERNAME',
    'DB_PASSWORD',
    'JWT_SECRET',
    'ENCRYPTION_KEY',
    'ADMIN_RESET_PASSWORD_KEY',
    'ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_ENABLED',
    'ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_USERNAME',
    'ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_PASSWORD'
) |
    ForEach-Object { Set-TrimmedEnv -Name $_ }

if (-not (Test-Path $javaExe)) {
    throw "Java runtime not found: $javaExe"
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

$needsBuild = -not (Test-Path $jarPath)
if (-not $needsBuild) {
    $sourceLatest = Get-NewestWriteTime -Paths $sourcePaths
    $jarLatest = (Get-Item $jarPath).LastWriteTime
    $needsBuild = $sourceLatest -gt $jarLatest
}

if ($needsBuild) {
    $env:JAVA_HOME = Join-Path $rootDir '.tools\jdk-17.0.18+8'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Push-Location $backendDir
    try {
        & .\gradlew.bat bootJar
        if ($LASTEXITCODE -ne 0) {
            throw "Backend build failed."
        }
    }
    finally {
        Pop-Location
    }

    $jarFile = Get-CurrentBackendJar
    $jarPath = if ($jarFile) { $jarFile.FullName } else { Join-Path $backendDir 'build\libs\odds-monitor-backend.jar' }
}

if (-not (Test-Path $jarPath)) {
    throw "Backend jar not found: $jarPath"
}

Push-Location $backendDir
try {
    $process = Start-Process `
        -FilePath $javaExe `
        -ArgumentList @('-jar', $jarPath) `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -NoNewWindow `
        -Wait `
        -PassThru
    exit $process.ExitCode
}
finally {
    Pop-Location
}




