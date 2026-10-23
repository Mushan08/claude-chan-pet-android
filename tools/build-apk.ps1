param(
    [string]$JdkHome = "E:\android-toolchain\jdk-17",
    [string]$SdkRoot = "E:\android-toolchain\sdk",
    [string]$BuildTools = "35.0.0",
    [string]$Platform = "android-35"
)

$ErrorActionPreference = "Stop"
$project = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildDir = Join-Path $project "build\manual"
$distDir = Join-Path $project "dist"
$keyDir = Join-Path $env:USERPROFILE ".android"

if (-not $buildDir.StartsWith($project, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "build dir escaped the project: $buildDir"
}

$javac = Join-Path $JdkHome "bin\javac.exe"
$keytool = Join-Path $JdkHome "bin\keytool.exe"
$androidJar = Join-Path $SdkRoot "platforms\$Platform\android.jar"
$buildToolsDir = Join-Path $SdkRoot "build-tools\$BuildTools"
$aapt2 = Join-Path $buildToolsDir "aapt2.exe"
$d8 = Join-Path $buildToolsDir "d8.bat"
$zipalign = Join-Path $buildToolsDir "zipalign.exe"
$apksigner = Join-Path $buildToolsDir "apksigner.bat"

foreach ($tool in @($javac, $keytool, $androidJar, $aapt2, $d8, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "missing tool: $tool"
    }
}

$env:JAVA_HOME = $JdkHome
$env:PATH = (Join-Path $JdkHome "bin") + ";" + $env:PATH

if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $buildDir, $distDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $buildDir "gen"), (Join-Path $buildDir "classes"), (Join-Path $buildDir "dex") | Out-Null

$manifest = Join-Path $project "app\src\main\AndroidManifest.xml"
$res = Join-Path $project "app\src\main\res"
$assets = Join-Path $project "app\src\main\assets"
$javaSrc = Join-Path $project "app\src\main\java"

Write-Host "== aapt2 compile =="
& $aapt2 compile --dir $res -o (Join-Path $buildDir "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link =="
& $aapt2 link `
    -o (Join-Path $buildDir "base.apk") `
    -I $androidJar `
    --manifest $manifest `
    -R (Join-Path $buildDir "res.zip") `
    --java (Join-Path $buildDir "gen") `
    -A $assets `
    --auto-add-overlay `
    --min-sdk-version 26 `
    --target-sdk-version 35 `
    -0 webp
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac =="
$sources = @(Get-ChildItem $javaSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem (Join-Path $buildDir "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options `
    -classpath $androidJar `
    -d (Join-Path $buildDir "classes") $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== d8 =="
$classes = @(Get-ChildItem (Join-Path $buildDir "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& $d8 --lib $androidJar --min-api 26 --output (Join-Path $buildDir "dex") $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== package dex =="
& python (Join-Path $PSScriptRoot "add_dex.py") `
    (Join-Path $buildDir "base.apk") `
    (Join-Path $buildDir "dex\classes.dex") `
    (Join-Path $buildDir "unsigned.apk")
if ($LASTEXITCODE -ne 0) { throw "add_dex failed" }

Write-Host "== zipalign =="
& $zipalign -f 4 (Join-Path $buildDir "unsigned.apk") (Join-Path $buildDir "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$keystore = Join-Path $keyDir "debug.keystore"
if (-not (Test-Path -LiteralPath $keystore)) {
    Write-Host "== generate debug keystore =="
    New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
    & $keytool -genkeypair -keystore $keystore -alias androiddebugkey `
        -storepass android -keypass android `
        -dname "CN=Android Debug,O=Android,C=US" `
        -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$apk = Join-Path $distDir "claude-chan-pet-android-0.1.0.apk"
Write-Host "== sign =="
& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --out $apk (Join-Path $buildDir "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "== verify =="
& $apksigner verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$sizeMb = [math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 2)
Write-Host "APK: $apk ($sizeMb MB)"
