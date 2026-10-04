<#
.SYNOPSIS
Starts a packaged VocaBoost launcher with --smoke-test and fails unless it exits with 0 in time.

.DESCRIPTION
With --smoke-test the app opens a new database in a temporary folder (never the user's data), waits
until its main window is shown, and quits with exit code 0, or prints why it failed and quits with 1.
This catches a launcher or trimmed runtime that cannot start the app, e.g. a missing Java module.
The script prints the app's output and stops with a non-zero exit code when the smoke test fails or
is still running after -TimeoutSeconds. scripts/smoke-test.sh does the same on Linux and macOS.

.EXAMPLE
.\scripts\smoke-test.ps1 target\dist\VocaBoost\VocaBoost.exe
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$Launcher,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 3.0

if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) {
    throw "$Launcher is not a launcher; build the app image first (scripts/package-windows.ps1)."
}
$launcherPath = (Resolve-Path -LiteralPath $Launcher).Path
$output = New-TemporaryFile
$errors = New-TemporaryFile
try {
    # The launcher of a Windows app image is a GUI program, which PowerShell does not wait for by itself.
    $process = Start-Process -FilePath $launcherPath -ArgumentList "--smoke-test" -NoNewWindow -PassThru `
        -RedirectStandardOutput $output.FullName -RedirectStandardError $errors.FullName
    # Keeps the process handle open, without which ExitCode stays empty after the process has exited.
    $null = $process.Handle
    $finished = $process.WaitForExit($TimeoutSeconds * 1000)
    if (-not $finished) {
        $process.Kill()
        $process.WaitForExit()
    }
    Get-Content -LiteralPath $output.FullName, $errors.FullName
    if (-not $finished) {
        throw "Smoke test failed: $Launcher --smoke-test was still running after $TimeoutSeconds s."
    }
    if ($process.ExitCode -ne 0) {
        throw "Smoke test failed: $Launcher --smoke-test exited with $($process.ExitCode)."
    }
    Write-Host "Smoke test passed: $Launcher showed its main window on a new database and exited with 0." -ForegroundColor Green
} finally {
    Remove-Item -LiteralPath $output.FullName, $errors.FullName -ErrorAction SilentlyContinue
}
