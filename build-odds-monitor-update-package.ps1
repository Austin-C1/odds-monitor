param(
    [switch]$IncludeJavaRuntime
)

$ErrorActionPreference = 'Stop'

$rootDir = (Resolve-Path (Split-Path -Parent $MyInvocation.MyCommand.Path)).Path
$desktopDir = [Environment]::GetFolderPath('Desktop')
$backendDir = Join-Path $rootDir 'backend'
$frontendDir = Join-Path $rootDir 'frontend'
$gradleCmd = Join-Path $backendDir 'gradlew.bat'
$javaHome = Join-Path $rootDir '.tools\jdk-17.0.18+8'
$frontendPackageJsonPath = Join-Path $frontendDir 'package.json'
$backendBuildFilePath = Join-Path $backendDir 'build.gradle.kts'

function Get-VersionFromBuildFiles {
    $frontendVersion = (Get-Content -Path $frontendPackageJsonPath -Raw | ConvertFrom-Json).version
    $backendBuildFileContent = Get-Content -Path $backendBuildFilePath -Raw
    $backendVersionMatch = [regex]::Match($backendBuildFileContent, '(?m)^version\s*=\s*"([^"]+)"')
    if (-not $backendVersionMatch.Success) {
        throw "Unable to read backend version from $backendBuildFilePath"
    }

    $backendVersion = $backendVersionMatch.Groups[1].Value.Trim()
    if ($frontendVersion.Trim() -ne $backendVersion) {
        throw "Frontend version '$frontendVersion' does not match backend version '$backendVersion'."
    }

    return $backendVersion
}

function Copy-RequiredFile {
    param(
        [string]$Source,
        [string]$Destination
    )

    if (-not (Test-Path $Source)) {
        throw "Required file not found: $Source"
    }

    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Write-Utf8File {
    param(
        [string]$Path,
        [string]$Content
    )

    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

$version = Get-VersionFromBuildFiles
$packageDirName = "odds-monitor-update-v$version"
$packageDir = Join-Path $desktopDir $packageDirName
$zipPath = Join-Path $desktopDir "$packageDirName.zip"
$jarPath = Join-Path $backendDir "build\libs\odds-monitor-backend-$version.jar"

foreach ($path in @($packageDir, $zipPath)) {
    if (Test-Path $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}

Push-Location $frontendDir
try {
    & npm run build
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path $javaHome)) {
    throw "Bundled Java runtime not found: $javaHome"
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

Push-Location $backendDir
try {
    & $gradleCmd bootJar
    if ($LASTEXITCODE -ne 0) {
        throw "Backend bootJar build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path $jarPath)) {
    throw "Backend jar not found after build: $jarPath"
}

New-Item -ItemType Directory -Path $packageDir -Force | Out-Null
Copy-RequiredFile -Source $jarPath -Destination (Join-Path $packageDir "backend\build\libs\odds-monitor-backend-$version.jar")
Copy-RequiredFile -Source (Join-Path $rootDir 'launch-odds-monitor.ps1') -Destination (Join-Path $packageDir 'launch-odds-monitor.ps1')
Copy-RequiredFile -Source (Join-Path $rootDir 'launch-odds-monitor.cmd') -Destination (Join-Path $packageDir 'launch-odds-monitor.cmd')
Copy-RequiredFile -Source (Join-Path $rootDir 'start-odds-backend.ps1') -Destination (Join-Path $packageDir 'start-odds-backend.ps1')
Copy-RequiredFile -Source (Join-Path $rootDir 'start-odds-backend.cmd') -Destination (Join-Path $packageDir 'start-odds-backend.cmd')
Copy-RequiredFile -Source (Join-Path $rootDir 'scripts\serve-odds-frontend.ps1') -Destination (Join-Path $packageDir 'scripts\serve-odds-frontend.ps1')

New-Item -ItemType Directory -Path (Join-Path $packageDir 'frontend') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $frontendDir 'dist') -Destination (Join-Path $packageDir 'frontend') -Recurse -Force

if ($IncludeJavaRuntime) {
    New-Item -ItemType Directory -Path (Join-Path $packageDir '.tools') -Force | Out-Null
    Copy-Item -LiteralPath $javaHome -Destination (Join-Path $packageDir '.tools') -Recurse -Force
}

$files = Get-ChildItem -LiteralPath $packageDir -Recurse -File -Force |
    ForEach-Object { $_.FullName.Substring($packageDir.Length + 1).Replace('\', '/') } |
    Where-Object { $_ -ne 'odds-monitor-update.json' } |
    Sort-Object

$manifest = [ordered]@{
    app = 'odds-monitor'
    version = $version
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    preserves = @('.env', 'config', 'data', 'logs', 'backups', 'updates', 'accounts', 'telegram settings')
    files = $files
} | ConvertTo-Json -Depth 5

Write-Utf8File -Path (Join-Path $packageDir 'odds-monitor-update.json') -Content $manifest

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}

$archive = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $packageDir -Recurse -File -Force | ForEach-Object {
        $entryName = $_.FullName.Substring($packageDir.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
}
finally {
    $archive.Dispose()
}

Write-Output "Update package version: $version"
Write-Output "Update package directory: $packageDir"
Write-Output "Update package zip: $zipPath"
Write-Output "Included Java runtime: $IncludeJavaRuntime"
