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

function Get-Utf8StringFromBase64 {
    param([string]$Value)

    return [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

function Assert-TextInFile {
    param(
        [string]$Path,
        [string]$Needle,
        [string]$Description
    )

    if (-not (Test-Path $Path)) {
        throw "Expected file not found for $Description`: $Path"
    }

    $content = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    if (-not $content.Contains($Needle)) {
        throw "Expected text missing for $Description`: $Needle"
    }
}

function assertPackagedAssetContains {
    param(
        [string]$Needle,
        [string]$Description
    )

    $distDir = Join-Path $packageDir 'frontend\dist'
    $assetFiles = Get-ChildItem -LiteralPath $distDir -Recurse -File -Force |
        Where-Object { $_.Extension -in @('.html', '.js', '.css') }

    foreach ($file in $assetFiles) {
        $content = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
        if ($content.Contains($Needle)) {
            return
        }
    }

    throw "Packaged frontend asset missing $Description ($Needle)."
}

$version = Get-VersionFromBuildFiles
$packageDirName = "odds-monitor-full-v$version"
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
    $env:VERSION = $version
    $env:GIT_TAG = "v$version"
    $env:GITHUB_REPO_URL = 'https://github.com/Austin-C1/odds-monitor'
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
New-Item -ItemType Directory -Path (Join-Path $packageDir '.tools') -Force | Out-Null
Copy-Item -LiteralPath $javaHome -Destination (Join-Path $packageDir '.tools') -Recurse -Force

Assert-TextInFile `
    -Path (Join-Path $packageDir 'start-odds-backend.ps1') `
    -Needle 'ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_ENABLED' `
    -Description 'packaged default admin login'

assertPackagedAssetContains -Needle (Get-Utf8StringFromBase64 '5rWL6K+V5qih5byP') -Description 'notification test mode'
assertPackagedAssetContains -Needle (Get-Utf8StringFromBase64 '5oqV5rOo5oiQ5Yqf5py65Zmo5Lq6') -Description 'betting success bot'
assertPackagedAssetContains -Needle 'AdsPower' -Description 'AdsPower betting integration'

$readme = @"
Odds Monitor v$version

Start:
1. Install and start Docker Desktop first.
2. Extract this package, then double-click launch-odds-monitor.cmd.
3. Open http://127.0.0.1:18881/login.

Default login:
Username: 123456
Password: 123456

This full package includes:
- Backend jar
- Frontend dist
- Bundled Java runtime
- Docker MySQL launch scripts
- Notification test mode
- Betting success bot
- AdsPower betting execution

Old installs should use odds-monitor-update-v$version.zip. New machines or machines missing the runtime should use this full package.
"@

Write-Utf8File -Path (Join-Path $packageDir 'README.txt') -Content $readme

$files = Get-ChildItem -LiteralPath $packageDir -Recurse -File -Force |
    ForEach-Object { $_.FullName.Substring($packageDir.Length + 1).Replace('\', '/') } |
    Where-Object { $_ -ne 'odds-monitor-full.json' } |
    Sort-Object

$manifest = [ordered]@{
    app = 'odds-monitor'
    packageType = 'full-install'
    version = $version
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    includesJavaRuntime = $true
    defaultLogin = '123456 / 123456'
    entrypoints = @('launch-odds-monitor.cmd', 'launch-odds-monitor.ps1')
    files = $files
} | ConvertTo-Json -Depth 5

Write-Utf8File -Path (Join-Path $packageDir 'odds-monitor-full.json') -Content $manifest

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

Write-Output "Full package version: $version"
Write-Output "Full package directory: $packageDir"
Write-Output "Full package zip: $zipPath"
Write-Output "Included Java runtime: true"
