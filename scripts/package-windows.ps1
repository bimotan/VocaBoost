<#
.SYNOPSIS
Builds VocaBoost and packages it with jpackage as a Windows app image (default) or installer.

.DESCRIPTION
Runs the Maven Wrapper (clean package, with the tests unless -SkipTests), builds a trimmed Java
runtime with jdeps and jlink, and hands it to jpackage. The version comes from pom.xml. The script
stops with a non-zero exit code as soon as a step fails.

Needs a JDK 17 or newer (JAVA_HOME, or its bin folder on PATH); the wrapper downloads Maven.
-PackageType exe or msi also needs the WiX Toolset on PATH (WiX 3 for JDK 17 to 21).
The script also runs under PowerShell 7 on Linux and macOS (without -Console), where it builds
that platform's app image; scripts/package.sh does the same without PowerShell.

.EXAMPLE
.\scripts\package-windows.ps1

.EXAMPLE
.\scripts\package-windows.ps1 -PackageType exe

.EXAMPLE
.\scripts\package-windows.ps1 -Console -SkipTests
#>
[CmdletBinding()]
param(
    [ValidateSet("app-image", "exe", "msi")]
    [string]$PackageType = "app-image",
    # Opens a console window next to the app that shows its log output (jpackage --win-console).
    [switch]$Console,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 3.0

$OnWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Target = Join-Path $ProjectRoot "target"
$InputDir = Join-Path $Target "jpackage-input"
$RuntimeDir = Join-Path $Target "jpackage-runtime"
$OutputDir = Join-Path $Target "dist"
$AppName = "VocaBoost"
$MainClass = "com.vocabtrainer.app.VocabTrainerLauncher"
# The app icon in the format jpackage takes on each system (packaging/icons/make-icons.sh renders them).
$Icon = if ($OnWindows) {
    Join-Path $ProjectRoot "packaging/icons/vocaboost.ico"
} elseif ($IsMacOS) {
    Join-Path $ProjectRoot "packaging/icons/vocaboost.icns"
} else {
    Join-Path $ProjectRoot "src/main/resources/icons/vocaboost-512.png"
}
# Needed at run time but invisible to jdeps: TLS key exchange for the online dictionary and the AI
# provider (jdk.crypto.ec; part of java.base from JDK 22 on), GBK/GB18030 CSV files (jdk.charsets)
# and Chinese date and number formats (jdk.localedata, trimmed to $Locales).
$ExtraModules = @("jdk.crypto.ec", "jdk.charsets", "jdk.localedata")
$Locales = "en,zh"

# Runs a native tool and throws if it fails: $ErrorActionPreference does not cover exit codes.
function Invoke-Tool {
    param(
        [Parameter(Mandatory = $true)] [string]$Step,
        [Parameter(Mandatory = $true)] [string]$Path,
        [string[]]$Arguments = @()
    )
    Write-Host "==> $Step" -ForegroundColor Cyan
    & $Path @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

function Find-JdkTool {
    param([Parameter(Mandatory = $true)] [string]$Name)
    $fileName = if ($OnWindows) { "$Name.exe" } else { $Name }
    # The same JDK as the Maven Wrapper, which also prefers JAVA_HOME.
    if ($env:JAVA_HOME) {
        $candidate = Join-Path (Join-Path $env:JAVA_HOME "bin") $fileName
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
        throw "JAVA_HOME points to $env:JAVA_HOME, but $candidate does not exist. Point JAVA_HOME to a JDK 17 or newer (jdeps, jlink and jpackage are not part of a JRE)."
    }
    $command = Get-Command $fileName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) {
        return $command.Path
    }
    throw "$Name was not found. Install a JDK 17 or newer (jdeps, jlink and jpackage are not part of a JRE) and set JAVA_HOME or add its bin folder to PATH."
}

# Check the tools first, so a missing one does not show up only after the build and the tests.
if ($Console -and -not $OnWindows) {
    throw "-Console is only available on Windows."
}
if (-not (Test-Path -LiteralPath $Icon -PathType Leaf)) {
    throw "The app icon $Icon is missing."
}
$java = Find-JdkTool "java"
$jdeps = Find-JdkTool "jdeps"
$jlink = Find-JdkTool "jlink"
$jpackage = Find-JdkTool "jpackage"
if ($PackageType -ne "app-image") {
    $wix3 = (Get-Command "candle.exe" -ErrorAction SilentlyContinue) -and (Get-Command "light.exe" -ErrorAction SilentlyContinue)
    $wix4 = Get-Command "wix.exe" -ErrorAction SilentlyContinue
    if (-not ($wix3 -or $wix4)) {
        throw "-PackageType $PackageType needs the WiX Toolset on PATH (WiX 3 for JDK 17 to 21). Install it or build the default app-image."
    }
}

$mvnw = Join-Path $ProjectRoot $(if ($OnWindows) { "mvnw.cmd" } else { "mvnw" })
$mavenArgs = @("clean", "package")
if ($SkipTests) {
    $mavenArgs += "-DskipTests"
}
if ($env:VOCABOOST_MAVEN_REPO) {
    $mavenArgs = @("-Dmaven.repo.local=$env:VOCABOOST_MAVEN_REPO") + $mavenArgs
}
Push-Location $ProjectRoot
try {
    Invoke-Tool "Building and testing with Maven" $mvnw $mavenArgs
} finally {
    Pop-Location
}

# Written by the jar build from pom.xml, so the version is never repeated here.
$pomProperties = Join-Path (Join-Path $Target "maven-archiver") "pom.properties"
$project = Get-Content -Raw -LiteralPath $pomProperties | ConvertFrom-StringData
$Version = $project.version
$JarName = "$($project.artifactId)-$Version.jar"
# jpackage wants a plain numeric version, so 1.2.0-SNAPSHOT is packaged as 1.2.0.
$AppVersion = ($Version -split "-")[0]
if ($AppVersion -notmatch '^\d+(\.\d+){0,2}$') {
    throw "The pom version '$Version' does not start with a numeric version that jpackage accepts, like 1.2.0."
}

# The app jar and its runtime jars (copied to target/lib by the package phase).
New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
Copy-Item -LiteralPath (Join-Path $Target $JarName) -Destination $InputDir
Copy-Item -Path (Join-Path (Join-Path $Target "lib") "*.jar") -Destination $InputDir
$jars = @(Get-ChildItem -LiteralPath $InputDir -Filter "*.jar" | ForEach-Object { $_.FullName })

$jdepsOutput = Invoke-Tool "Finding the Java modules the app uses (jdeps)" $jdeps (
    @("--print-module-deps", "--ignore-missing-deps", "--multi-release", "17") + $jars)
$detected = @($jdepsOutput) | Where-Object { "$_" -match '^[\w.]+(,[\w.]+)*$' } | Select-Object -Last 1
if (-not $detected) {
    throw "jdeps printed no module list: $jdepsOutput"
}
$available = @(Invoke-Tool "Listing the JDK's modules" $java @("--list-modules") | ForEach-Object { ("$_" -split "@")[0] })
$modules = @("$detected".Trim() -split ",") + @($ExtraModules | Where-Object { $available -contains $_ }) | Select-Object -Unique
$moduleList = $modules -join ","

$jlinkVersion = @(Invoke-Tool "Checking the jlink version" $jlink @("--version"))
$jdkMajor = [int](("$($jlinkVersion[0])".Trim() -split '[.+-]')[0])
$jlinkArgs = @(
    "--add-modules", $moduleList,
    "--strip-debug",
    "--no-man-pages",
    "--no-header-files",
    "--strip-native-commands",
    $(if ($jdkMajor -ge 21) { "--compress=zip-6" } else { "--compress=2" }),
    "--output", $RuntimeDir
)
if ($modules -contains "jdk.localedata") {
    $jlinkArgs += "--include-locales=$Locales"
}
Invoke-Tool "Building a trimmed Java runtime (jlink)" $jlink $jlinkArgs

$packageArgs = @(
    "--type", $PackageType,
    "--name", $AppName,
    "--app-version", $AppVersion,
    "--input", $InputDir,
    "--main-jar", $JarName,
    "--main-class", $MainClass,
    "--runtime-image", $RuntimeDir,
    "--dest", $OutputDir,
    "--vendor", "VocaBoost",
    "--description", "JavaFX and SQLite vocabulary trainer",
    "--icon", $Icon
)
if ($Console) {
    $packageArgs += "--win-console"
}
if ($PackageType -ne "app-image") {
    $packageArgs += @("--win-menu", "--win-shortcut")
}
Invoke-Tool "Packaging the $PackageType (jpackage)" $jpackage $packageArgs

if ($PackageType -eq "app-image") {
    $result = Join-Path $OutputDir $AppName
    $launcher = if ($OnWindows) { Join-Path $result "$AppName.exe" } else { Join-Path (Join-Path $result "bin") $AppName }
} else {
    $result = Join-Path $OutputDir "$AppName-$AppVersion.$PackageType"
    $launcher = $result
}
if (-not (Test-Path -LiteralPath $launcher)) {
    throw "jpackage finished, but $launcher is missing."
}
$files = if ((Get-Item -LiteralPath $result).PSIsContainer) { Get-ChildItem -LiteralPath $result -Recurse -File } else { Get-Item -LiteralPath $result }
$sizeMb = ($files | Measure-Object -Property Length -Sum).Sum / 1MB

Write-Host ("Packaged {0} {1}: {2} ({3:N0} MB)" -f $AppName, $Version, $result, $sizeMb) -ForegroundColor Green
Write-Host "Java modules: $moduleList"
if ($PackageType -eq "app-image") {
    Write-Host "Run: $launcher"
}
