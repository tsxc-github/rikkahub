#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$DocsRoot = (Join-Path $PSScriptRoot '..'),
    [Parameter(Mandatory)][string]$SshRepo,
    [Parameter(Mandatory)][string]$OpsRepo
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$DocsRoot = (Resolve-Path -LiteralPath $DocsRoot).Path
$script:assertions = 0
function Assert-True([bool]$Condition,[string]$Description) {
    if (!$Condition) { throw "FAIL: $Description" }
    $script:assertions++
}
$parsed = 0
Get-ChildItem -LiteralPath (Join-Path $DocsRoot 'scripts') -Recurse -File -Filter '*.ps1' | ForEach-Object {
    $tokens=$null; $errors=$null
    [System.Management.Automation.Language.Parser]::ParseFile($_.FullName,[ref]$tokens,[ref]$errors) | Out-Null
    Assert-True ($errors.Count -eq 0) "parse $($_.Name): $errors"
    $parsed++
}
$manifest = Get-Content -LiteralPath (Join-Path $DocsRoot 'MANIFEST.json') -Raw | ConvertFrom-Json
foreach ($entry in $manifest.files) {
    $file = Join-Path $DocsRoot $entry.path
    Assert-True (Test-Path -LiteralPath $file -PathType Leaf) "exists $($entry.path)"
    Assert-True ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -eq $entry.sha256) "hash $($entry.path)"
}
$pwsh = (Get-Process -Id $PID).Path
function Invoke-Script([string]$File,[string[]]$Arguments) {
    $si = [Diagnostics.ProcessStartInfo]::new($pwsh)
    $si.UseShellExecute = $false
    $si.RedirectStandardOutput = $true
    $si.RedirectStandardError = $true
    foreach ($arg in (@('-NoProfile','-File',$File) + $Arguments)) { $si.ArgumentList.Add($arg) }
    $p = [Diagnostics.Process]::Start($si)
    $stdout = $p.StandardOutput.ReadToEndAsync()
    $stderr = $p.StandardError.ReadToEndAsync()
    $p.WaitForExit()
    $result = @{code=$p.ExitCode; stdout=$stdout.GetAwaiter().GetResult(); stderr=$stderr.GetAwaiter().GetResult()}
    $p.Dispose()
    return $result
}
$runner = Join-Path $DocsRoot 'scripts/Run-Build.ps1'
foreach ($pair in @(@($SshRepo,'.sshprobe'),@($OpsRepo,'.ops'))) {
    $repo=$pair[0]; $suffix=$pair[1]
    $before = & git -C $repo status --porcelain
    Assert-True ($LASTEXITCODE -eq 0) "git status before $suffix"
    $r = Invoke-Script -File $runner -Arguments @('-RepoRoot',$repo)
    Assert-True ($r.code -eq 0) "default Inspect $suffix : $($r.stderr)"
    Assert-True ($r.stdout.Contains("debugPackage=me.rerere.rikkahub$suffix")) "package $suffix"
    Assert-True ($r.stdout.Contains('INSPECT_ONLY')) "read-only default $suffix"
    $head = (& git -C $repo rev-parse HEAD) -join ''
    Assert-True ($r.stdout.Contains("head=$head")) "real HEAD $suffix"
    $r = Invoke-Script -File $runner -Arguments @('-RepoRoot',$repo,'-Stage','Release')
    Assert-True ($r.code -ne 0) "release denied $suffix"
    Assert-True ($r.stderr.Contains('AllowOfficialPackage')) "release denied before SDK/build $suffix"
    $after = & git -C $repo status --porcelain
    Assert-True (($before -join "`n") -ceq ($after -join "`n")) "source status unchanged $suffix"
}
$temp = Join-Path ([IO.Path]::GetTempPath()) ('rikka-handoff-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    Copy-Item -LiteralPath $DocsRoot -Destination (Join-Path $temp 'docs') -Recurse
    $copy = Join-Path $temp 'docs'
    $r = Invoke-Script -File (Join-Path $copy 'scripts/Update-Manifest.ps1') -Arguments @('-DocsRoot',$copy)
    Assert-True ($r.code -eq 0) "manifest updater $($r.stderr)"
    $updated = Get-Content -LiteralPath (Join-Path $copy 'MANIFEST.json') -Raw | ConvertFrom-Json
    Assert-True ($updated.files.Count -eq $manifest.files.Count) 'manifest file count'
    foreach ($entry in $updated.files) {
        Assert-True ((Get-FileHash -LiteralPath (Join-Path $copy $entry.path) -Algorithm SHA256).Hash.ToLowerInvariant() -eq $entry.sha256) "updated hash $($entry.path)"
    }
} finally { Remove-Item -LiteralPath $temp -Recurse -Force }
Write-Output "HANDOFF_TEST_OK parsedScripts=$parsed files=$($manifest.files.Count) assertions=$script:assertions"
Write-Output 'No Gradle build, Android, Windows, phone IPC or VS Code runtime validation was performed.'
