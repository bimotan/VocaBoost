param(
    [string]$MavenPath = "",
    [ValidateSet("app-image", "exe")]
    [string]$PackageType = "app-image"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$AppName = "VocaBoost"
$Version = "1.0.0"
$JarName = "vocab-trainer-$Version.jar"
$MainClass = "com.vocabtrainer.app.VocabTrainerLauncher"
$InputDir = Join-Path $ProjectRoot "target\jpackage-input"
$OutputDir = Join-Path $ProjectRoot "target\dist"

function Resolve-Maven {
    param([string]$RequestedPath)

    if ($RequestedPath -and (Test-Path -LiteralPath $RequestedPath)) {
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }

    $fromPath = Get-Command mvn -ErrorAction SilentlyContinue
    if ($fromPath) {
        return $fromPath.Source
    }

    $intellijMaven = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd"
    if (Test-Path -LiteralPath $intellijMaven) {
        return $intellijMaven
    }

    throw "Maven was not found. Install Maven, add it to PATH, or pass -MavenPath."
}

$mvn = Resolve-Maven $MavenPath
New-Item -ItemType Directory -Force -Path $InputDir, $OutputDir | Out-Null

$mavenArgs = @("clean", "package", "dependency:copy-dependencies", "-DincludeScope=runtime", "-DoutputDirectory=$InputDir")
if ($env:VOCABOOST_MAVEN_REPO) {
    $mavenArgs = @("-Dmaven.repo.local=$env:VOCABOOST_MAVEN_REPO") + $mavenArgs
}
& $mvn @mavenArgs
Copy-Item -LiteralPath (Join-Path $ProjectRoot "target\$JarName") -Destination (Join-Path $InputDir $JarName) -Force

$jpackage = Get-Command jpackage -ErrorAction SilentlyContinue
if (-not $jpackage) {
    throw "jpackage was not found. Use JDK 17+ and make sure the JDK bin directory is in PATH."
}

$packageArgs = @(
    "--type", $PackageType,
    "--name", $AppName,
    "--app-version", $Version,
    "--input", $InputDir,
    "--main-jar", $JarName,
    "--main-class", $MainClass,
    "--dest", $OutputDir,
    "--vendor", "VocaBoost",
    "--description", "JavaFX and SQLite vocabulary trainer"
)

if ($PackageType -eq "exe") {
    $packageArgs += @("--win-menu", "--win-shortcut")
}

& $jpackage @packageArgs

Write-Host "Package created in: $OutputDir"
if ($PackageType -eq "app-image") {
    Write-Host "Run: target\dist\VocaBoost\VocaBoost.exe"
} else {
    Write-Host "Installer: target\dist\VocaBoost-$Version.exe"
}
