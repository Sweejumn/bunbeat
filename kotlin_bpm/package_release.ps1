# Package the Kotlin BPM engine as release artifacts (no Gradle needed).
#
#   .\package_release.ps1 [-Version 1.0.0]
#
# Produces (under build\):
#   bunbeat-bpm.jar              runnable fat jar (engine + CLI + kotlin-stdlib)
#   bunbeat-bpm-<v>-src.zip      sources + tools + docs
#   run-bunbeat-bpm.bat          Windows launcher for the jar
# NOTE: keep this file ASCII-only; Windows PowerShell 5.1 parses no-BOM UTF-8 as ANSI.
param(
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "D:\02_DevTools\Java\jdk-21.0.11"
$KOTLIN_LIB = "D:\02_DevTools\IntelliJ IDEA 2026.2.3\plugins\Kotlin\kotlinc\lib"
$ROOT = $PSScriptRoot
$BUILD = Join-Path $ROOT "build"
$JAR = Join-Path $BUILD "parity.jar"
$FAT = Join-Path $BUILD "bunbeat-bpm.jar"
$STD = Join-Path $KOTLIN_LIB "kotlin-stdlib.jar"
$JDK_JAR = Join-Path $env:JAVA_HOME "bin\jar.exe"

if (-not (Test-Path $JDK_JAR)) { throw "jar.exe not found: $JDK_JAR" }

# 1) compile
& (Join-Path $ROOT "build.ps1")
if (-not (Test-Path $JAR)) { throw "compile did not produce $JAR" }

# 2) fat jar = parity.jar + kotlin-stdlib.jar, minus signature files
$stage = Join-Path $BUILD "fat"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Push-Location $stage
& $JDK_JAR xf $JAR
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "extract parity.jar failed" }
& $JDK_JAR xf $STD
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "extract kotlin-stdlib.jar failed" }
Pop-Location
Get-ChildItem $stage -Recurse -Include "*.SF", "*.RSA", "*.DSA" -File | Remove-Item -Force

$manifest = Join-Path $BUILD "MANIFEST.MF"
# manifest lines must stay under 72 bytes; these are short on purpose
@(
    "Manifest-Version: 1.0",
    "Main-Class: com.bunbeat.bpm.CliMainKt",
    "Implementation-Title: bunbeat-bpm",
    "Implementation-Version: $Version",
    ""
) -join "`r`n" | Set-Content -Path $manifest -Encoding ASCII -NoNewline

if (Test-Path $FAT) { Remove-Item -Force $FAT }
& $JDK_JAR cfm $FAT $manifest -C $stage .
if ($LASTEXITCODE -ne 0) { throw "fat jar creation failed" }
Write-Host "fat jar OK: $FAT ($((Get-Item $FAT).Length) bytes)"

# 3) Windows launcher next to the jar (release asset, not part of the build dir)
$bat = Join-Path $BUILD "run-bunbeat-bpm.bat"
@(
    "@echo off",
    "rem Bunbeat BPM engine - drag a .wav file onto this file, or run: run-bunbeat-bpm.bat <file.wav>",
    "rem switch the console to UTF-8 so non-ASCII file names survive",
    "chcp 65001 >nul",
    "java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar ""%~dp0bunbeat-bpm.jar"" %*",
    "rem keep the window open (typical use: drag a .wav onto this file)",
    "pause"
) -join "`r`n" | Set-Content -Path $bat -Encoding ASCII

# 4) source zip
$srcZip = Join-Path $BUILD "bunbeat-bpm-$Version-src.zip"
if (Test-Path $srcZip) { Remove-Item -Force $srcZip }
$tmp = Join-Path $BUILD "pkgroot"
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
$pkg = Join-Path $tmp "bunbeat-bpm-$Version"
New-Item -ItemType Directory -Force -Path $pkg | Out-Null
Copy-Item -Recurse (Join-Path $ROOT "src") $pkg
Copy-Item -Recurse (Join-Path $ROOT "tools") $pkg
foreach ($f in @("README.md", "build.ps1", "package_release.ps1", "run_parity.ps1")) {
    $p = Join-Path $ROOT $f
    if (Test-Path $p) { Copy-Item $p $pkg }
}
Copy-Item $bat $pkg
Compress-Archive -Path (Join-Path $pkg "*") -DestinationPath $srcZip -Force
Remove-Item -Recurse -Force $tmp
Write-Host "source zip OK: $srcZip ($((Get-Item $srcZip).Length) bytes)"
Write-Host "DONE"
