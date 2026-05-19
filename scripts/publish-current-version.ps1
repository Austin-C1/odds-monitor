param(
    [string]$Message = '',
    [string]$Branch = '',
    [switch]$SkipChecks,
    [switch]$CreatePr,
    [switch]$Yes,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$rootDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendDir = Join-Path $rootDir 'backend'
$frontendDir = Join-Path $rootDir 'frontend'
$localGh = Join-Path $rootDir '.tools\gh\bin\gh.exe'
$javaHome = Join-Path $rootDir '.tools\jdk-17.0.18+8'

function Write-Step {
    param([string]$Message)
    Write-Host "[publish] $Message"
}

function Invoke-Checked {
    param(
        [string]$Label,
        [scriptblock]$Action
    )

    Write-Step $Label
    if ($DryRun) {
        return
    }

    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed."
    }
}

function Get-GitHubCli {
    $command = Get-Command gh -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    if (Test-Path $localGh) {
        return $localGh
    }
    return $null
}

function Get-RepositoryUrl {
    $remote = (git remote get-url origin).Trim()
    if ($remote -match '^https://github\.com/(?<owner>[^/]+)/(?<repo>[^/]+?)(\.git)?$') {
        return "https://github.com/$($Matches.owner)/$($Matches.repo)"
    }
    if ($remote -match '^git@github\.com:(?<owner>[^/]+)/(?<repo>[^/]+?)(\.git)?$') {
        return "https://github.com/$($Matches.owner)/$($Matches.repo)"
    }
    return $remote
}

function Confirm-Scope {
    if ($Yes -or $DryRun) {
        return
    }

    $answer = Read-Host 'Stage and commit all listed changes? Type YES to continue'
    if ($answer -cne 'YES') {
        throw 'Publish canceled.'
    }
}

Set-Location $rootDir

$currentBranch = if ($Branch) { $Branch } else { (git branch --show-current).Trim() }
if ([string]::IsNullOrWhiteSpace($currentBranch)) {
    throw 'Current checkout is not on a publishable Git branch.'
}

$status = git status --short
$aheadLine = git status -sb

Write-Step "Current branch: $currentBranch"
Write-Step 'Current status:'
if ($status) {
    $status | ForEach-Object { Write-Host $_ }
} else {
    Write-Host 'No local uncommitted changes.'
}

if (-not $SkipChecks) {
    if (Test-Path $javaHome) {
        $env:JAVA_HOME = $javaHome
        $env:Path = "$javaHome\bin;$env:Path"
    }
    Invoke-Checked 'Backend test and package' {
        Push-Location $backendDir
        try {
            & .\gradlew.bat test bootJar
        } finally {
            Pop-Location
        }
    }
    Invoke-Checked 'Frontend tests' {
        Push-Location $frontendDir
        try {
            & npm test
        } finally {
            Pop-Location
        }
    }
    Invoke-Checked 'Frontend lint' {
        Push-Location $frontendDir
        try {
            & npm run lint
        } finally {
            Pop-Location
        }
    }
    Invoke-Checked 'Frontend build' {
        Push-Location $frontendDir
        try {
            & npm run build
        } finally {
            Pop-Location
        }
    }
}

if ($status) {
    Confirm-Scope
    Invoke-Checked 'Stage current changes' {
        git add -A
    }

    $staged = git diff --cached --name-only
    if (-not $staged) {
        Write-Step 'No staged changes to commit.'
    } else {
        if ([string]::IsNullOrWhiteSpace($Message)) {
            $Message = Read-Host 'Commit message'
            if ([string]::IsNullOrWhiteSpace($Message)) {
                throw 'Commit message cannot be empty.'
            }
        }
        Invoke-Checked "Create commit: $Message" {
            git commit -m $Message
        }
    }
} elseif ($aheadLine -notmatch '\[ahead ') {
    Write-Step 'Nothing to publish.'
    exit 0
}

Invoke-Checked "Push branch: $currentBranch" {
    git push -u origin $currentBranch
}

$repoUrl = Get-RepositoryUrl
$commit = (git rev-parse HEAD).Trim()
Write-Host ''
Write-Host "Branch URL: $repoUrl/tree/$currentBranch"
Write-Host "Commit URL: $repoUrl/commit/$commit"

if ($CreatePr) {
    $gh = Get-GitHubCli
    if (-not $gh) {
        Write-Host 'GitHub CLI was not found; PR creation skipped.'
        exit 0
    }

    & $gh auth status 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'GitHub CLI is not logged in. Run this once:'
        Write-Host "  `"$gh`" auth login"
        exit 0
    }

    Invoke-Checked 'Create or show PR' {
        & $gh pr view --json url --jq .url
        if ($LASTEXITCODE -ne 0) {
            & $gh pr create --draft --fill --head $currentBranch
        }
    }
}
