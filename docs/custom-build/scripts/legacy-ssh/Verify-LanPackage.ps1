#requires -Version 7.0
# Static artifact verification, not Android/Windows device validation.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$base = '/mnt/coding/rikkahub-build'
$repo = "$base/src/rikkahub-ssh-p0"
$logs = "$base/logs/ssh-lan"
$env:JAVA_HOME = '/usr/lib/jvm/java-21-openjdk-amd64'
$env:PATH = "$env:JAVA_HOME/bin:" + $env:PATH
$tools = "$base/android-sdk/build-tools/37.0.0"
$main = "$repo/app/build/outputs/apk/github/debug/app-github-arm64-v8a-debug.apk"
$test = "$repo/app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk"
$certificate = '7fb5c6c9015483f1c11518069f4817924d4c07ca5c937744a93d18831a2c9630'
$result = [ordered]@{ head = (& git -C $repo rev-parse HEAD).Trim(); apks = @(); jvm = @{} }
foreach ($entry in @(@($main,'me.rerere.rikkahub.sshprobe'),@($test,'me.rerere.rikkahub.sshprobe.test'))) {
    $apk = $entry[0]; $package = $entry[1]
    $badging = @(& "$tools/aapt2" dump badging $apk)
    if ($LASTEXITCODE -ne 0 -or -not ($badging -match "^package: name='$([regex]::Escape($package))'")) { throw "Unexpected APK package: $apk" }
    if ($apk -eq $main -and -not ($badging -match "versionCode='195' versionName='3.1.2'")) { throw 'Unexpected main APK version' }
    $signing = @(& "$tools/apksigner" verify --print-certs $apk)
    if ($LASTEXITCODE -ne 0 -or -not (($signing -join "`n").Contains($certificate))) { throw "APK signer verification failed: $apk" }
    $result.apks += @{ path=$apk; package=$package; bytes=(Get-Item -LiteralPath $apk).Length; sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant(); certificate=$certificate }
}
$manifest = (& "$tools/aapt2" dump xmltree --file AndroidManifest.xml $main) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Main manifest dump failed' }
foreach ($marker in @('SshDevService','PROPERTY_SPECIAL_USE_FGS_SUBTYPE','ACCESS_NETWORK_STATE')) { if (-not $manifest.Contains($marker)) { throw "Missing manifest marker: $marker" } }
$testManifest = (& "$tools/aapt2" dump xmltree --file AndroidManifest.xml $test) -join "`n"
if ($LASTEXITCODE -ne 0 -or $testManifest -notmatch 'targetPackage[^\r\n]*"me\.rerere\.rikkahub\.sshprobe"') { throw 'Wrong instrumentation target' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($main)
try {
    $allDex = [Text.StringBuilder]::new()
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('.dex')) {
            $stream=$entry.Open(); $buffer=[IO.MemoryStream]::new()
            try { $stream.CopyTo($buffer); $null=$allDex.Append([Text.Encoding]::ASCII.GetString($buffer.ToArray())) } finally { $stream.Dispose(); $buffer.Dispose() }
        }
    }
    $dex = $allDex.ToString()
    foreach ($marker in @('SshLanNetworks','SshListenMode','bindAddress','bindInterface','SettingSshPage','SshDevService')) { if (-not $dex.Contains($marker)) { throw "Missing LAN DEX marker: $marker" } }
    if (-not ($zip.Entries.FullName -match 'ops-v2/query.sh$')) { throw 'ops-v2 query resource missing' }
} finally { $zip.Dispose() }
foreach ($suite in @(@('app','testGithubDebugUnitTest'),@('workspace','testDebugUnitTest'))) {
    $counts = @{ tests=0; failures=0; errors=0; skipped=0 }
    $files = @(Get-ChildItem -LiteralPath "$repo/$($suite[0])/build/test-results/$($suite[1])" -File -Filter 'TEST-*.xml')
    if (-not $files.Count) { throw 'Missing JVM XML reports' }
    foreach ($file in $files) {
        [xml]$xml = Get-Content -Raw -LiteralPath $file.FullName
        foreach ($name in @('tests','failures','errors','skipped')) { $counts[$name] += [int]$xml.testsuite.GetAttribute($name) }
    }
    if ($counts.failures -or $counts.errors) { throw 'JVM failures found in XML reports' }
    $result.jvm[$suite[0]] = $counts
}
$result.manifest = 'service + specialUse + network permission verified'
$result.dex = 'LAN classes/address fields + ops-v2 query verified'
$json = $result | ConvertTo-Json -Depth 6
$json | Set-Content -LiteralPath "$logs/verification.json" -Encoding utf8NoBOM
Write-Output $json
