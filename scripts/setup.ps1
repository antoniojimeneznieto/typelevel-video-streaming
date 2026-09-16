#Requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$Check,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$Distribution = 'Ubuntu'
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') {
    throw 'Use bash scripts/setup.sh on macOS or Linux.'
}
if ($Distribution -like 'docker-*') {
    throw 'Choose your development distribution, not a Docker-managed distribution.'
}

function Get-WslOutput {
    param([string[]]$Arguments)
    # Windows PowerShell can surface native stderr as errors even when redirected.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & wsl.exe @Arguments 2>$null
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return @{ Text = (($output -join "`n") -replace "`0", '').Trim(); Code = $code }
}

$hasWsl = $null -ne (Get-Command wsl.exe -ErrorAction SilentlyContinue)
$installed = @()
$wslVersion = $null
if ($hasWsl) {
    $listing = Get-WslOutput -Arguments @('--list', '--quiet')
    if ($listing.Code -eq 0) {
        $installed = @($listing.Text -split "`n" | ForEach-Object { $_.Trim() })
    }
    if ($installed -contains $Distribution) {
        $details = Get-WslOutput -Arguments @('--list', '--verbose')
        $pattern = '(?m)^\s*\*?\s*' + [regex]::Escape($Distribution) + '\s+.+?\s+(\d+)\s*$'
        if ($details.Code -eq 0 -and $details.Text -match $pattern) {
            $wslVersion = [int]$Matches[1]
        }
    }
}

$desktopPaths = @(
    (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe')
)
$hasDocker = ($null -ne (Get-Command docker.exe -ErrorAction SilentlyContinue)) -or
    @($desktopPaths | Where-Object { Test-Path -LiteralPath $_ }).Count -gt 0

Write-Host 'Workshop setup: Windows uses WSL 2 and Docker Desktop.'
Write-Host "Development distribution: $Distribution"
if ($installed -contains $Distribution) {
    Write-Host "Keep existing distribution (WSL version: $wslVersion)."
} else {
    Write-Host "Install WSL and $Distribution if needed; restart and Linux user setup may be required."
}
if ($hasDocker) {
    Write-Host 'Keep the existing Docker installation; verify access from WSL.'
} else {
    Write-Host 'Install Docker Desktop using WinGet, with interactive installer/agreement prompts.'
}
Write-Host 'Install missing Java, sbt, and shell tools inside WSL using scripts/setup.sh.'
Write-Host 'No automatic restart, Docker replacement, or Windows execution-policy changes.'

if (-not $Check -and (Read-Host 'Continue with this setup? [y/N]') -notmatch '^(y|yes)$') {
    Write-Host 'Setup cancelled; nothing installed.'
    exit 0
}

if (-not $hasWsl) {
    Write-Host 'Install WSL using https://learn.microsoft.com/windows/wsl/install, then rerun this script.'
    exit 1
}
if ($installed -notcontains $Distribution) {
    if ($Check) {
        Write-Host 'The development distribution is missing. Rerun without -Check to install it.'
        exit 1
    }
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent()
    )
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Write-Host 'Open PowerShell as administrator and rerun this script to install WSL.'
        exit 1
    }
    & wsl.exe --install --distribution $Distribution --no-launch
    if ($LASTEXITCODE -notin @(0, 3010)) {
        throw 'WSL installation did not complete. Follow the installer instructions before retrying.'
    }
    Write-Host "Restart Windows if requested, launch $Distribution from Start, and create your Linux user."
    Write-Host 'Then rerun this script in your normal PowerShell session.'
    exit 1
}
if ($wslVersion -ne 2) {
    Write-Host "WSL 2 is required. Inspect with: wsl --list --verbose"
    Write-Host "Back up your distribution before converting it: wsl --set-version $Distribution 2"
    exit 1
}

$linuxUser = Get-WslOutput -Arguments @('--distribution', $Distribution, '--exec', 'id', '-u')
if ($linuxUser.Code -ne 0 -or $linuxUser.Text -notmatch '^[1-9][0-9]*$') {
    Write-Host "Open $Distribution and finish its non-root Linux user setup, then rerun this script."
    exit 1
}
$linuxDocker = Get-WslOutput -Arguments @('--distribution', $Distribution, '--exec', 'sh', '-c', 'command -v docker')
if ($linuxDocker.Code -eq 0) {
    $hasDocker = $true
    Write-Host 'Docker is already available inside WSL; its installation will not be replaced.'
}
if (-not $hasDocker) {
    if ($Check) {
        Write-Host 'Docker Desktop was not found on Windows; checking the selected WSL environment next.'
    } else {
        if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
            Write-Host 'Install Docker Desktop from https://docs.docker.com/desktop/setup/install/windows-install/'
            Write-Host 'Then rerun this script. WinGet is available through Microsoft App Installer.'
            exit 1
        }
        & winget.exe install --id Docker.DockerDesktop --exact --source winget --interactive --no-upgrade
        if ($LASTEXITCODE -ne 0) {
            throw 'Docker Desktop installation did not complete. Follow the installer instructions before retrying.'
        }
        Write-Host "Open Docker Desktop, review its terms, and enable WSL integration for $Distribution."
        Write-Host 'Use Linux containers. Rerun this script when Docker Desktop is running.'
        exit 1
    }
}

$windowsSetupPath = Join-Path $PSScriptRoot 'setup.sh'
$linuxSetup = Get-WslOutput -Arguments @('--distribution', $Distribution, '--exec', 'wslpath', '-a', '-u', $windowsSetupPath)
if ($linuxSetup.Code -ne 0 -or [string]::IsNullOrWhiteSpace($linuxSetup.Text)) {
    Write-Host "Open this repository inside $Distribution and run: bash scripts/setup.sh"
    exit 1
}
Write-Host "Docker Desktop: Settings > Resources > WSL Integration > $Distribution must be enabled."
$linuxArguments = @('--distribution', $Distribution, '--exec', 'bash', '--', $linuxSetup.Text)
if ($Check) { $linuxArguments += '--check' }
& wsl.exe @linuxArguments
$setupExitCode = $LASTEXITCODE
if ($setupExitCode -eq 0 -and -not $Check) {
    Write-Host "Inside $Distribution, open this repository and run: bash scripts/start.sh"
}
exit $setupExitCode
