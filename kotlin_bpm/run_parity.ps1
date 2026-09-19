# Build the Kotlin port, run it over every WAV in a manifest, then diff
# against the Dart golden output.
#
#   .\run_parity.ps1                                  # dev set
#   .\run_parity.ps1 -Manifest <m.json> -Out <o.json> -Expected <e.json>
#   .\run_parity.ps1 -SkipBuild
#
# NOTE: this file is deliberately ASCII-only. The shell on this machine is
# Windows PowerShell 5.1 (no pwsh 7); it reads .ps1 files with no BOM using
# the ANSI code page, so non-ASCII comments here get mangled and can corrupt
# parsing of the following line.
param(
    [string]$Manifest = "C:\Users\123\Desktop\muzrun\research_tmp\parity\manifest.json",
    [string]$Out      = "C:\Users\123\Desktop\muzrun\research_tmp\parity\actual_kotlin.json",
    [string]$Expected = "C:\Users\123\Desktop\muzrun\research_tmp\parity\expected_dart.json",
    [string]$Main     = "com.bunbeat.bpm.ParityMainKt",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
# Manifest paths contain non-ASCII (Japanese/Chinese) filenames: force UTF-8
# everywhere instead of relying on the shell default code page.
$OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$env:JAVA_HOME = "D:\02_DevTools\Java\jdk-21.0.11"
$KOTLIN_LIB = "D:\02_DevTools\IntelliJ IDEA 2026.2.3\plugins\Kotlin\kotlinc\lib"
$stdlib = Join-Path $KOTLIN_LIB "kotlin-stdlib.jar"
$JAR = Join-Path $PSScriptRoot "build\parity.jar"

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}
if (-not (Test-Path $JAR)) { throw "jar missing: $JAR (run build.ps1)" }

$json = [System.IO.File]::ReadAllText($Manifest, [System.Text.Encoding]::UTF8)
$doc = $json | ConvertFrom-Json
$paths = @($doc.paths)
if ($paths.Count -eq 0) { throw "manifest has no paths: $Manifest" }
Write-Host "running Kotlin port on $($paths.Count) wav files"

# The Kotlin harness writes per-file progress to stderr. PowerShell 5.1 wraps
# native stderr as an ErrorRecord, which $ErrorActionPreference="Stop" would
# turn into a fatal error, so relax it for the call and log stderr to a file.
$log = Join-Path $PSScriptRoot "build\parity.stderr.log"
$ErrorActionPreference = "Continue"
& "$env:JAVA_HOME\bin\java" -Xmx3G -cp "$JAR;$stdlib" $Main $Out @paths 2> $log
$rc = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($rc -ne 0) {
    Write-Host "--- last 20 stderr lines ---"
    Get-Content $log -Tail 20 -Encoding UTF8 | Write-Host
    throw "ParityMain failed (exit $rc)"
}
Write-Host "stderr log: $log"

python (Join-Path $PSScriptRoot "tools\compare.py") $Expected $Out
