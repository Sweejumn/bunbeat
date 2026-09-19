# 编译（可选运行）Kotlin 版 BPM 库 + parity harness。
# 用 IntelliJ 自带的 kotlinc（K2JVMCompiler），无需 Gradle。
#
#   .\build.ps1                 # 只编译
#   .\build.ps1 -Run <out.json> <file.wav> ...
param(
    [switch]$Run,
    [string[]]$Args2 = @()
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "D:\02_DevTools\Java\jdk-21.0.11"
$KOTLIN_LIB = "D:\02_DevTools\IntelliJ IDEA 2026.2.3\plugins\Kotlin\kotlinc\lib"
$ROOT = $PSScriptRoot
$OUT = Join-Path $ROOT "build"
$JAR = Join-Path $OUT "parity.jar"

if (-not (Test-Path $KOTLIN_LIB)) { throw "kotlinc lib not found: $KOTLIN_LIB" }
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

$compilerCp = (Get-ChildItem $KOTLIN_LIB -Filter "*.jar" | ForEach-Object { $_.FullName }) -join ";"
$stdlib = Join-Path $KOTLIN_LIB "kotlin-stdlib.jar"

$sources = @()
$sources += Get-ChildItem (Join-Path $ROOT "src\main\kotlin") -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $ROOT "tools") -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) { throw "no .kt sources found under $ROOT" }

Write-Host "compiling $($sources.Count) Kotlin files -> $JAR"
& "$env:JAVA_HOME\bin\java" -Xmx1500M -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -nowarn -cp $stdlib -d $JAR @sources 2>&1 |
    Where-Object { $_ -notmatch "^info:" } | ForEach-Object { Write-Host $_ }

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $JAR)) { throw "kotlin compile FAILED" }
Write-Host "compiled OK: $JAR"

if ($Run) {
    & "$env:JAVA_HOME\bin\java" -Xmx3G -cp "$JAR;$stdlib" com.bunbeat.bpm.ParityMainKt @Args2
}
