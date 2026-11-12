#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$base = '/mnt/coding/rikkahub-build'
$out = "$base/downloads/ssh-lan-v3.1.2"
$verification = Get-Content -Raw -LiteralPath "$base/logs/ssh-lan/verification.json" | ConvertFrom-Json
$main = $verification.apks | Where-Object package -eq 'me.rerere.rikkahub.sshprobe'
$test = $verification.apks | Where-Object package -eq 'me.rerere.rikkahub.sshprobe.test'
if (-not $main -or -not $test) { throw 'Missing verified APK metadata' }
New-Item -ItemType Directory -Force -Path "$out/artifacts" | Out-Null
foreach ($item in @(@($main,'ssh-lan-v3.1.2-arm64-debug.apk'),@($test,'ssh-lan-v3.1.2-androidTest.apk'))) {
    $metadata=$item[0]; $target=Join-Path "$out/artifacts" $item[1]
    if ((Get-FileHash -LiteralPath $metadata.path -Algorithm SHA256).Hash -ne $metadata.sha256) { throw 'Source APK changed after verification' }
    Copy-Item -LiteralPath $metadata.path -Destination $target -Force
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $metadata.sha256) { throw 'Copied APK mismatch' }
}
$files = @('README.md','BUILD-STATUS.txt','LAN-TESTING.md','windows/Test-RikkaSshLan.ps1','artifacts/ssh-lan-v3.1.2-arm64-debug.apk','artifacts/ssh-lan-v3.1.2-androidTest.apk')
$lines = foreach ($file in $files) {
    $path = Join-Path $out $file
    (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $file
}
$lines | Set-Content -LiteralPath "$out/SHA256SUMS" -Encoding utf8NoBOM
$files += 'SHA256SUMS'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = "$base/downloads/ssh-lan-v3.1.2-windows.zip"
$stream = [IO.File]::Open($zipPath,[IO.FileMode]::Create)
$archive = [IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$false)
try {
    foreach ($file in $files) {
        $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $out $file),$file,[IO.Compression.CompressionLevel]::Fastest)
    }
} finally { $archive.Dispose(); $stream.Dispose() }
$archive = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    if ($archive.Entries.Count -ne $files.Count) { throw 'Wrong package entry count' }
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notin $files) { throw 'Unexpected package entry' }
        $input = $entry.Open()
        try { $input.CopyTo([IO.Stream]::Null) } finally { $input.Dispose() }
    }
} finally { $archive.Dispose() }
$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
($hash + '  ' + [IO.Path]::GetFileName($zipPath)) | Set-Content -LiteralPath "$zipPath.sha256" -Encoding utf8NoBOM
$result = @{ path=$zipPath; bytes=(Get-Item -LiteralPath $zipPath).Length; sha256=$hash; head=$verification.head; files=$files } | ConvertTo-Json -Depth 4
$result | Set-Content -LiteralPath "$base/logs/ssh-lan/package.json" -Encoding utf8NoBOM
Write-Output $result
