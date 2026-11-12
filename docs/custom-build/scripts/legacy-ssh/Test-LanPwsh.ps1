#requires -Version 7.0
# Executable pwsh syntax/policy tests. No ADB, no network listeners, no private keys.
param([string]$Base = '/mnt/coding/rikkahub-build')
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$count = 0
$scripts = @("$Base/Build-Lan.ps1", "$Base/Watch-LanBuild.ps1", "$Base/Test-RikkaSshLan.ps1")
foreach ($file in $scripts) {
    $tokens = $null; $errors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($file,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw "PowerShell parse failure: $file :: $errors" }
    $count++
}
$cases = @(
    @('127.0.0.1',$true), @('10.0.0.1',$true), @('172.16.0.1',$true), @('172.31.255.1',$true), @('192.168.43.1',$true),
    @('172.32.0.1',$false), @('8.8.8.8',$false), @('0.0.0.0',$false), @('::1',$false), @('192.168.01.1',$false),
    @('localhost',$false), @('192.168.1',$false), @('192.168.1.1;id',$false), @("192.168.1.1`nListenAddress 0.0.0.0",$false)
)
$log = Join-Path $Base 'logs/ssh-lan/pwsh-policy-case.log'
foreach ($case in $cases) {
    & "$PSHOME/pwsh" -NoLogo -NoProfile -File "$Base/Test-RikkaSshLan.ps1" -Mode ValidateHost -HostName $case[0] *> $log
    $accepted = $LASTEXITCODE -eq 0
    if ($accepted -ne $case[1]) { throw "Host validation mismatch for $($case[0]); log=$log" }
    $count++
}
Write-Host "POWERSHELL_TESTS_PASSED=$count (3 parsers + 14 host-policy cases)"
