# Windows PowerShell 5.1 / PowerShell 7. Does not install VS Code Server.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('Install','Workspaces','Auto','Computer','Report','Cleanup')]
    [string]$Mode,
    [string]$WorkspaceId,
    [string]$PublicKeyPath,
    [string]$Serial,
    [string]$AdbPath = 'adb',
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot '..\artifacts')
)
$ErrorActionPreference = 'Stop'
$Package = 'me.rerere.rikkahub.sshprobe'
$Runner = 'me.rerere.rikkahub.sshprobe.test/androidx.test.runner.AndroidJUnitRunner'
$TestClass = 'me.rerere.rikkahub.ssh.SshP0DeviceTest'
$Adb = (Get-Command $AdbPath -ErrorAction Stop).Source
$DeviceArgs = @()
if ($Serial) { $DeviceArgs = @('-s', $Serial) }
$Reports = Join-Path $PSScriptRoot 'reports'
New-Item -ItemType Directory -Force -Path $Reports | Out-Null
function Invoke-AdbChecked {
    param([string[]]$Arguments)
    & $Adb @DeviceArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed (exit=$LASTEXITCODE)." }
}
function Save-ProbeReport {
    $report = @(& $Adb @DeviceArgs exec-out run-as $Package cat files/ssh-p0-report.txt 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'No probe report available. Check installation/workspace/test output.' }
    $path = Join-Path $Reports 'p0-report.txt'
    $report | Set-Content -Encoding UTF8 -Path $path
    $report | ForEach-Object { Write-Host $_ }
    Write-Host "Saved report: $path"
    return ($report -join "`n")
}
if ($Mode -in @('Auto','Computer')) {
    if ($WorkspaceId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') {
        throw 'Pass the local, READY test workspace UUID using -WorkspaceId.'
    }
}
switch ($Mode) {
    'Install' {
        $main = Join-Path $ArtifactDirectory 'ssh-p0-v3.1.2-arm64-debug.apk'
        $test = Join-Path $ArtifactDirectory 'ssh-p0-v3.1.2-androidTest.apk'
        foreach ($apk in @($main,$test)) {
            if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Missing artifact: $apk" }
        }
        Invoke-AdbChecked -Arguments @('get-state')
        Invoke-AdbChecked -Arguments @('install','-r',$main)
        Invoke-AdbChecked -Arguments @('install','-r',$test)
        Invoke-AdbChecked -Arguments @('shell','pm','list','instrumentation')
        Write-Host 'Open the isolated SSH-probe app. Create a LOCAL test workspace; install Rootfs and dependencies. Do not import real data.'
    }
    'Workspaces' {
        Invoke-AdbChecked -Arguments @('shell','run-as',$Package,'ls','files/workspaces')
    }
    'Report' { $null = Save-ProbeReport }
    'Cleanup' {
        Invoke-AdbChecked -Arguments @('shell','am','force-stop',$Package)
        & $Adb @DeviceArgs forward --remove tcp:2222
        Write-Host 'Probe stopped; requested removal of ADB port 2222 forward. No packages/data were deleted.'
    }
    { $_ -in @('Auto','Computer') } {
        $argsForTest = @('shell','am','instrument','-w','-r','-e','class',$TestClass,'-e','workspaceId',$WorkspaceId)
        $ownedForward = $false
        if ($Mode -eq 'Computer') {
            if (-not $PublicKeyPath -or -not (Test-Path -LiteralPath $PublicKeyPath -PathType Leaf)) {
                throw 'Computer mode requires -PublicKeyPath pointing to your NEW TEST .pub file. Never supply a private key.'
            }
            $key = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $PublicKeyPath).Path).Trim()
            if ($key -notmatch '^ssh-ed25519 [A-Za-z0-9+/=]+(?: [^\r\n]*)?$') {
                throw 'Only one OpenSSH Ed25519 PUBLIC key line is accepted. Private keys are forbidden.'
            }
            $key64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($key + "`n"))
            Invoke-AdbChecked -Arguments @('forward','--no-rebind','tcp:2222','tcp:2222')
            $ownedForward = $true
            $argsForTest += @('-e','mode','computer','-e','publicKeyBase64',$key64)
            Write-Host 'Keep this terminal open. In a SECOND PowerShell window run Report mode; wait for READY FOR COMPUTER before SSH. Maximum window: 15 minutes.'
        }
        $argsForTest += $Runner
        try {
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $stdout = @(& $Adb @DeviceArgs @argsForTest 2>&1 | Tee-Object -FilePath (Join-Path $Reports "instrument-$Mode-$stamp.txt"))
            $adbExit = $LASTEXITCODE
            $stdout | ForEach-Object { Write-Host $_ }
            $report = Save-ProbeReport
            $joined = $stdout -join "`n"
            if ($adbExit -ne 0 -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' -or $joined -notmatch 'OK \(\d+ tests?\)') {
                throw 'Instrumentation did not pass. Preserve reports and stop; do not proceed to P1.'
            }
            if ($report -notmatch 'P0 AUTOMATED CHECKS PASSED' -or $report -match 'CLEANUP ERROR:|REMOVE TEST DATA FAILED:') {
                throw 'Probe report is incomplete or cleanup failed. Preserve reports.'
            }
            Write-Host 'App lifecycle/automated probe PASSED. Full P0 still requires real computer SSH/SFTP/interactive checks; VS Code compatibility is NOT certified.'
        } finally {
            # Force-stop ONLY the isolated probe. This also covers failed/aborted instrumentation.
            & $Adb @DeviceArgs shell am force-stop $Package
            if ($ownedForward) { & $Adb @DeviceArgs forward --remove tcp:2222 }
        }
    }
}
