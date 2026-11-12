#requires -Version 7.0
param([int]$IntervalSeconds = 30, [int]$MaxChecks = 121)
$ErrorActionPreference = 'Stop'
$dir = '/mnt/coding/rikkahub-build/logs/ssh-ipc'
$previous = ''
for ($i = 0; $i -lt $MaxChecks; $i++) {
    $codes = [ordered]@{}
    Get-ChildItem -LiteralPath $dir -Filter '*exit-code' -File | Sort-Object Name | ForEach-Object { $codes[$_.Name] = (Get-Content -Raw -LiteralPath $_.FullName).Trim() }
    $driver = Join-Path $dir 'driver.log'
    $tail = if (Test-Path -LiteralPath $driver) { @(Get-Content -LiteralPath $driver -Tail 5) } else { @() }
    $state = @{ stages = $codes; last = $tail } | ConvertTo-Json -Depth 4 -Compress
    if ($state -cne $previous) { Write-Host ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $state); $previous = $state }
    if ($codes.Contains('exit-code')) {
        $summary = Join-Path $dir 'summary.txt'
        if (Test-Path -LiteralPath $summary) { Get-Content -LiteralPath $summary }
        Write-Host ('IPC_BUILD_MONITOR_COMPLETE: driver exit=' + $codes['exit-code'])
        exit 0
    }
    if ($i -lt $MaxChecks - 1) { Start-Sleep -Seconds $IntervalSeconds }
}
Write-Host 'IPC_BUILD_MONITOR_LIMIT_REACHED'
exit 2
