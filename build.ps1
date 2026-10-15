param([string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk")
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$bt = Join-Path $Sdk 'build-tools\36.1.0'
$platform = Join-Path $Sdk 'platforms\android-36.1\android.jar'
$out = Join-Path $PSScriptRoot 'app\build\manual'
$apkOutput = Join-Path $PSScriptRoot 'dist\WeakNet-1.0.1.apk'
foreach ($required in @($platform,"$bt\aapt2.exe","$bt\lib\d8.jar","$bt\lib\apksigner.jar")) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing build dependency: $required" }
}
# Never package classes left over from renamed/deleted Java files.
if (Test-Path -LiteralPath $out) {
    $resolvedOutput = (Resolve-Path -LiteralPath $out).Path
    $expectedOutput = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'app\build\manual'))
    if ($resolvedOutput -ne $expectedOutput -or !( $resolvedOutput.StartsWith($PSScriptRoot + '\',[StringComparison]::OrdinalIgnoreCase))) {
        throw "Refusing to clean unexpected output directory: $resolvedOutput"
    }
    Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
}
New-Item -ItemType Directory -Force $out,"$out\aar","$out\classes","$out\generated","$out\dex","$PSScriptRoot\dist" | Out-Null
function Run([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed: $LASTEXITCODE" }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$aar = Join-Path $PSScriptRoot 'app\libs\hev-socks5-tunnel-2.18.0.aar'
if ((Get-FileHash -LiteralPath $aar -Algorithm SHA256).Hash -ne '15EC8ED121663B562C99CAA5BB602D1009F24E5B09E733438B81988F12FEAAAB') {
    throw 'Unexpected native AAR checksum; verify the dependency before building.'
}
$zip = [IO.Compression.ZipFile]::OpenRead($aar)
try { foreach ($entry in $zip.Entries) { if ($entry.Name) {
    $target = [IO.Path]::GetFullPath((Join-Path "$out\aar" $entry.FullName))
    if (!$target.StartsWith("$out\aar\",[StringComparison]::OrdinalIgnoreCase)) { throw 'Archive entry escaped extraction directory' }
    New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($target)) | Out-Null
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
} } } finally { $zip.Dispose() }
Run "$bt\aapt2.exe" @('compile','--dir','app/src/main/res','-o',"$out\res.zip")
# AGP uses namespace from Gradle; standalone aapt2 requires a package in its input manifest.
[xml]$manualManifest = Get-Content -LiteralPath 'app/src/main/AndroidManifest.xml' -Raw
$manualManifest.manifest.SetAttribute('package','cn.weaknet.lab')
$manualManifest.Save("$out\AndroidManifest.xml")
Run "$bt\aapt2.exe" @('link','-o',"$out\base.apk",'-I',$platform,'-A','app/src/main/assets','--manifest',"$out\AndroidManifest.xml",'--java',"$out\generated",'--min-sdk-version','26','--target-sdk-version','35','--version-code','2','--version-name','1.0.1',"$out\res.zip")
$sources = @(Get-ChildItem 'app/src/main/java',"$out\generated" -Recurse -Filter *.java | ForEach-Object FullName)
Run 'javac' (@('-encoding','UTF-8','--release','17','-classpath',"$platform;$out\aar\classes.jar",'-d',"$out\classes") + $sources)
Run 'jar' @('cf',"$out\app.jar",'-C',"$out\classes",'.')
Run 'java' @('-cp',"$bt\lib\d8.jar",'com.android.tools.r8.D8','--lib',$platform,'--min-api','26','--output',"$out\dex","$out\app.jar","$out\aar\classes.jar")
Copy-Item "$out\base.apk" "$out\unsigned.apk" -Force
$apk = [IO.Compression.ZipFile]::Open("$out\unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($dex in Get-ChildItem "$out\dex" -Filter 'classes*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,$dex.FullName,$dex.Name) | Out-Null
    }
    foreach ($lib in Get-ChildItem "$out\aar\jni" -Recurse -Filter *.so) {
        $relative = $lib.FullName.Substring(("$out\aar\jni\").Length).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,$lib.FullName,"lib/$relative") | Out-Null
    }
} finally { $apk.Dispose() }
Run "$bt\zipalign.exe" @('-f','-p','4',"$out\unsigned.apk","$out\aligned.apk")
$key = Join-Path $PSScriptRoot '.tools\weaknet-debug.jks'
New-Item -ItemType Directory -Force (Split-Path $key) | Out-Null
if (!(Test-Path $key)) {
    Run 'keytool' @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=WeakNet Local Debug,O=WeakNet,C=CN')
}
Run 'java' @('-jar',"$bt\lib\apksigner.jar",'sign','--ks',$key,'--ks-pass','pass:android','--key-pass','pass:android','--out',$apkOutput,"$out\aligned.apk")
Run 'java' @('-jar',"$bt\lib\apksigner.jar",'verify','--verbose',$apkOutput)
Get-FileHash $apkOutput -Algorithm SHA256
