#requires -Version 7.0
# Native Windows OpenSSH clients; private keys never leave the PC. LAN does not require ADB.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Install','ValidateHost','PinHost','Connect','Sftp','CheckStopped','Forward')][string]$Mode,
    [string]$HostName = '127.0.0.1',
    [ValidateRange(1024,65535)][int]$Port = 2222,
    [string]$IdentityFile = '.\p0-computer',
    [string]$ExpectedFingerprint,
    [string]$Serial,
    [string]$AdbPath = 'adb',
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot '..\artifacts')
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$parsed = $null
if (-not [Net.IPAddress]::TryParse($HostName,[ref]$parsed) -or
    $parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $parsed.ToString() -cne $HostName) {
    throw 'Use the exact numeric IPv4 shown by the phone; hostnames, shorthand, IPv6 and leading zeros are not accepted.'
}
$octets = $parsed.GetAddressBytes()
$private = $octets[0] -eq 10 -or ($octets[0] -eq 172 -and $octets[1] -ge 16 -and $octets[1] -le 31) -or ($octets[0] -eq 192 -and $octets[1] -eq 168)
if ($HostName -cne '127.0.0.1' -and -not $private) { throw 'Only phone RFC1918 LAN addresses or 127.0.0.1 are accepted; no public/wildcard endpoint.' }
if ($Mode -eq 'ValidateHost') { Write-Output "LAN_HOST_VALID=$HostName"; exit 0 }
$DeviceArgs = if ($Serial) { @('-s',$Serial) } else { @() }
$Hosts = Join-Path (Get-Location).Path ".rikka-ssh-hosts-$HostName-$Port"
$HostOption = 'UserKnownHostsFile="' + ($Hosts -replace '\\','/') + '"'
function Invoke-AdbChecked([string[]]$Arguments) {
    $adb = (Get-Command $AdbPath -ErrorAction Stop).Source
    & $adb @DeviceArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed, exit=$LASTEXITCODE" }
}
function Get-ClientArguments([switch]$Sftp) {
    if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) { throw 'Missing local test private key. Never upload/paste that private key into the phone.' }
    if (-not (Test-Path -LiteralPath $Hosts -PathType Leaf)) { throw 'Run PinHost with the current PHONE UI fingerprint first.' }
    $portFlag = if ($Sftp) { '-P' } else { '-p' }
    return @('-i',$IdentityFile,$portFlag,"$Port",'-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o',$HostOption,'-o','ConnectTimeout=5')
}
switch ($Mode) {
    'Install' {
        $packageRoot = Split-Path -Parent $ArtifactDirectory
        $manifest = Join-Path $packageRoot 'SHA256SUMS'
        $expected = @{}
        Get-Content -LiteralPath $manifest | ForEach-Object {
            if ($_ -match '^([0-9a-fA-F]{64})  (.+)$') { $expected[$Matches[2]] = $Matches[1] }
        }
        $names = @('ssh-lan-v3.1.2-arm64-debug.apk','ssh-lan-v3.1.2-androidTest.apk')
        foreach ($name in $names) {
            $file = Join-Path $ArtifactDirectory $name
            $hash = $expected["artifacts/$name"]
            if (-not $hash -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $hash) { throw "APK checksum missing/mismatched: $name" }
        }
        Invoke-AdbChecked -Arguments @('get-state')
        Invoke-AdbChecked -Arguments @('install','-r',(Join-Path $ArtifactDirectory $names[0]))
        Invoke-AdbChecked -Arguments @('install','-r','-t',(Join-Path $ArtifactDirectory $names[1]))
        Write-Host 'Isolated app upgraded. Open SSH settings, choose LAN/hotspot mode and its concrete address. Do not uninstall or run P0 Computer mode concurrently.'
    }
    'Forward' {
        if ($HostName -ne '127.0.0.1') { throw 'LAN/hotspot mode does not use ADB forwarding. Connect directly to the phone IP.' }
        Invoke-AdbChecked -Arguments @('forward','--no-rebind',"tcp:$Port","tcp:$Port")
    }
    'PinHost' {
        if (-not $ExpectedFingerprint) { throw 'Copy the expected fingerprint from the PHONE UI, not from the network scan.' }
        $wanted = [regex]::Match($ExpectedFingerprint, 'SHA256:[A-Za-z0-9+/]+').Value
        if (-not $wanted) { throw 'Expected fingerprint must include SHA256:...' }
        $scanFile = Join-Path ([IO.Path]::GetTempPath()) ('rikka-lan-host-' + [guid]::NewGuid().ToString('N'))
        try {
            $scan = @(& ssh-keyscan -T 5 -t ed25519 -p $Port $HostName 2>$null)
            if ($LASTEXITCODE -ne 0 -or $scan.Count -eq 0) { throw 'No host key received. Check phone RUNNING, selected IP, same Wi-Fi/hotspot, and AP/client isolation.' }
            [IO.File]::WriteAllLines($scanFile,$scan,[Text.UTF8Encoding]::new($false))
            $info = @(& ssh-keygen -lf $scanFile)
            if ($LASTEXITCODE -ne 0 -or $info.Count -ne 1) { throw 'Expected exactly one Ed25519 host key.' }
            $actual = [regex]::Match(($info -join ''), 'SHA256:[A-Za-z0-9+/]+').Value
            if ($actual -cne $wanted) { throw "HOST KEY MISMATCH: UI=$wanted received=$actual. Existing trust file is unchanged." }
            Copy-Item -LiteralPath $scanFile -Destination $Hosts -Force
            Write-Host "Pinned $HostName after exact fingerprint comparison: $actual"
        } finally { if (Test-Path -LiteralPath $scanFile) { Remove-Item -LiteralPath $scanFile } }
    }
    'Connect' {
        $clientArgs = Get-ClientArguments
        & ssh @clientArgs "root@$HostName"
        if ($LASTEXITCODE -ne 0) { throw "SSH exited with $LASTEXITCODE; do not disable host-key checking." }
    }
    'Sftp' {
        $clientArgs = Get-ClientArguments -Sftp
        & sftp @clientArgs "root@$HostName"
        if ($LASTEXITCODE -ne 0) { throw "SFTP exited with $LASTEXITCODE" }
    }
    'CheckStopped' {
        $clientArgs = Get-ClientArguments
        & ssh @clientArgs -o BatchMode=yes "root@$HostName" true
        if ($LASTEXITCODE -eq 0) { throw 'SSH STILL ACCEPTS COMMANDS: service is not stopped.' }
        Write-Host 'SSH command failed as expected after stopping. Also verify phone STOPPED, notification removed and old session disconnected. An auth/network failure alone is not process-cleanup proof.'
    }
}
