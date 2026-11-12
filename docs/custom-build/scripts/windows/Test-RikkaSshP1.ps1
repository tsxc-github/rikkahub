#requires -Version 7.0
# Run with pwsh. No private key is ever uploaded. No LAN/public listener is created.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Install','Forward','PinHost','Connect','CheckStopped','Cleanup')][string]$Mode,
    [string]$IdentityFile = '.\p0-computer',
    [string]$ExpectedFingerprint,
    [ValidateRange(1024,65535)][int]$Port = 2222,
    [string]$Serial,
    [string]$AdbPath = 'adb',
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot '..\artifacts')
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$Adb = (Get-Command $AdbPath -ErrorAction Stop).Source
$DeviceArgs = if ($Serial) { @('-s',$Serial) } else { @() }
$Package = 'me.rerere.rikkahub.sshprobe'
$Hosts = Join-Path (Get-Location).Path '.rikka-ssh-p1-known-hosts'
$HostOption = 'UserKnownHostsFile="' + ($Hosts -replace '\\','/') + '"'
function Invoke-AdbChecked([string[]]$Arguments) {
    & $Adb @DeviceArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed, exit=$LASTEXITCODE" }
}
function Get-SshArguments {
    if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) { throw 'Missing local test private key. It stays on this computer; do not paste/upload it to the app.' }
    if (-not (Test-Path -LiteralPath $Hosts -PathType Leaf)) { throw 'Run PinHost with the fingerprint shown by the phone UI first.' }
    return @('-i',$IdentityFile,'-p',"$Port",'-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o',$HostOption,'-o','ConnectTimeout=5')
}
switch ($Mode) {
    'Install' {
        $main = Join-Path $ArtifactDirectory 'ssh-p1-v3.1.2-arm64-debug.apk'
        $test = Join-Path $ArtifactDirectory 'ssh-p1-v3.1.2-androidTest.apk'
        $expected = @{
            $main = '96d0373aa49c8cf4be1e16924c4ae82fa56b2afdc321faa2e23c4e1f1a3df92a'
            $test = '52c65f99b1403f09e87a4e2d2222f78e22ba77c50c9ffe19314bb111906dcee9'
        }
        foreach ($file in @($main,$test)) {
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing APK: $file" }
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash -ne $expected[$file]) { throw "APK SHA256 mismatch: $file" }
        }
        Invoke-AdbChecked -Arguments @('get-state')
        Invoke-AdbChecked -Arguments @('install','-r',$main)
        Invoke-AdbChecked -Arguments @('install','-r','-t',$test)
        Write-Host 'Upgrade installed; existing isolated workspace is preserved. Open Settings -> SSH development connection. Do NOT run the P0 Computer probe while P1 uses the same port.'
    }
    'Forward' {
        Invoke-AdbChecked -Arguments @('forward','--no-rebind',"tcp:$Port","tcp:$Port")
        Write-Host 'ADB loopback forward created. Next: PinHost with the current fingerprint shown by the phone UI.'
    }
    'PinHost' {
        if (-not $ExpectedFingerprint) { throw 'Supply -ExpectedFingerprint copied from the phone UI.' }
        $wanted = [regex]::Match($ExpectedFingerprint, 'SHA256:[A-Za-z0-9+/]+').Value
        if (-not $wanted) { throw 'Supply -ExpectedFingerprint from the PHONE UI (not from keyscan output).' }
        $scanFile = Join-Path ([IO.Path]::GetTempPath()) ('rikka-p1-host-' + [guid]::NewGuid().ToString('N'))
        try {
            $scan = @(& ssh-keyscan -T 5 -t ed25519 -p $Port 127.0.0.1 2>$null)
            if ($LASTEXITCODE -ne 0 -or $scan.Count -eq 0) { throw 'No SSH host key received. Check phone RUNNING state and ADB forward.' }
            [IO.File]::WriteAllLines($scanFile, $scan, [Text.UTF8Encoding]::new($false))
            $info = @(& ssh-keygen -lf $scanFile)
            if ($LASTEXITCODE -ne 0 -or $info.Count -ne 1) { throw 'Could not verify exactly one scanned Ed25519 host key.' }
            $actual = [regex]::Match(($info -join ''), 'SHA256:[A-Za-z0-9+/]+').Value
            if ($actual -cne $wanted) { throw "HOST KEY MISMATCH. Phone=$wanted; received=$actual. No trust file was changed." }
            Copy-Item -LiteralPath $scanFile -Destination $Hosts -Force
            Write-Host "Host key pinned after exact comparison with the phone fingerprint: $actual"
        } finally { if (Test-Path -LiteralPath $scanFile) { Remove-Item -LiteralPath $scanFile } }
    }
    'Connect' {
        $sshArgs = Get-SshArguments
        & ssh @sshArgs root@127.0.0.1
        if ($LASTEXITCODE -ne 0) { throw "SSH ended with exit=$LASTEXITCODE. Preserve the message; do not disable host-key checking." }
    }
    'CheckStopped' {
        $sshArgs = Get-SshArguments
        & ssh @sshArgs -o BatchMode=yes root@127.0.0.1 true
        if ($LASTEXITCODE -eq 0) { throw 'SSH STILL ACCEPTS COMMANDS: do not declare the service stopped.' }
        Write-Host 'SSH command did not succeed. Also confirm phone STOPPED, notification removed, and an existing SSH session disconnected. Failure alone is NOT proof of process cleanup (authentication/ADB can also fail).'
    }
    'Cleanup' {
        Write-Host 'This explicitly force-stops ONLY the isolated probe app, and removes the ADB forward. No data is deleted.'
        Invoke-AdbChecked -Arguments @('shell','am','force-stop',$Package)
        & $Adb @DeviceArgs forward --remove "tcp:$Port"
    }
}
