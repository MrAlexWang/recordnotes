# 不依赖 Gradle 的 APK 构建脚本（Windows PowerShell）
# 用法: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jdk = Join-Path $root '.toolchain\jdk17'
$sdk = Join-Path $root '.toolchain\sdk'
$bt = Join-Path $sdk 'build-tools\35.0.0'
$platform = Join-Path $sdk 'platforms\android-35\android.jar'
$app = Join-Path $root 'app'
$build = Join-Path $root 'build'
$dist = Join-Path $root 'dist'

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$bt;" + $env:PATH

foreach ($p in @($jdk, $sdk, $bt, $platform)) {
    if (-not (Test-Path $p)) { throw "缺少工具链: $p" }
}

Write-Host '[1/8] 准备目录与 AAR' -ForegroundColor Cyan
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, $dist | Out-Null
python (Join-Path $root 'tools\prep_aar.py') | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'prep_aar 失败' }
$libs = Join-Path $build 'libs'
Copy-Item (Join-Path $root 'build-dl\kotlin-stdlib.jar') $libs -Force
Copy-Item (Join-Path $root 'build-dl\annotations.jar') $libs -Force

# 翻译引擎原生库（llama.cpp，arm64）
$trSo = Join-Path $root 'build-llama\out\arm64-v8a\libhytrans.so'
if (Test-Path $trSo) {
    New-Item -ItemType Directory -Force -Path (Join-Path $libs 'arm64-v8a') | Out-Null
    Copy-Item $trSo (Join-Path $libs 'arm64-v8a') -Force
    Write-Host ("   翻译引擎库 " + [math]::Round((Get-Item $trSo).Length/1MB,1) + " MB")
} else {
    Write-Host '   [warn] 未找到 libhytrans.so，翻译功能将不可用' -ForegroundColor Yellow
}

Write-Host '[2/8] 生成前端页面' -ForegroundColor Cyan
python (Join-Path $root 'tools\make_www.py') | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'make_www 失败' }

Write-Host '[3/8] 编译资源' -ForegroundColor Cyan
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $app 'res') -o (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host '[4/8] 链接资源与 assets（含 228MB 模型，稍慢）' -ForegroundColor Cyan
& (Join-Path $bt 'aapt2.exe') link `
    -I $platform `
    --manifest (Join-Path $app 'AndroidManifest.xml') `
    -R (Join-Path $build 'res.zip') `
    -A (Join-Path $app 'src\main\assets') `
    -0 onnx `
    --min-sdk-version 26 `
    --target-sdk-version 35 `
    --version-code 1 `
    --version-name '1.0' `
    --auto-add-overlay `
    -o (Join-Path $build 'base.apk')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '[5/8] 编译 Java' -ForegroundColor Cyan
$sources = Get-ChildItem -Path (Join-Path $app 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$deps = @('sherpa.jar', 'kotlin-stdlib.jar', 'annotations.jar') | ForEach-Object { Join-Path $libs $_ }
$cpAll = (($deps + $platform) -join ';')
$javacArgs = @('-encoding', 'UTF-8', '--release', '17', '-nowarn',
    '-cp', $cpAll,
    '-d', (Join-Path $build 'classes')) + $sources
& (Join-Path $jdk 'bin\javac.exe') @javacArgs
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '[6/8] 生成 dex' -ForegroundColor Cyan
& (Join-Path $jdk 'bin\jar.exe') --create --file (Join-Path $build 'classes.jar') -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'jar 失败' }
New-Item -ItemType Directory -Force -Path (Join-Path $build 'dex') | Out-Null
& (Join-Path $bt 'd8.bat') --release --min-api 26 --lib $platform --output (Join-Path $build 'dex') `
    (Join-Path $build 'classes.jar') (Join-Path $libs 'sherpa.jar') (Join-Path $libs 'kotlin-stdlib.jar') (Join-Path $libs 'annotations.jar')
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '[7/8] 合并 dex / so 并对齐' -ForegroundColor Cyan
python (Join-Path $root 'tools\merge_apk.py') `
    (Join-Path $build 'base.apk') `
    (Join-Path $build 'dex\classes.dex') `
    (Join-Path $build 'libs') `
    (Join-Path $build 'unsigned.apk') | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'merge 失败' }
& (Join-Path $bt 'zipalign.exe') -f -p 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '[8/8] 签名' -ForegroundColor Cyan
$ks = Join-Path $dist 'debug.keystore'
if (-not (Test-Path $ks)) {
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -v -keystore $ks -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US' | Out-Host
}
$apk = Join-Path $dist 'RecordNotes.apk'
if (Test-Path $apk) { Remove-Item $apk -Force }
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 26 --out $apk (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

& (Join-Path $bt 'apksigner.bat') verify --print-certs $apk | Select-Object -First 4 | Out-Host
Write-Host ("构建完成: " + $apk + "  " + [math]::Round((Get-Item $apk).Length / 1MB, 1) + " MB") -ForegroundColor Green
