#requires -Version 7.0
[CmdletBinding()]
param([string]$DocsRoot = (Join-Path $PSScriptRoot '..'))
$ErrorActionPreference = 'Stop'
$DocsRoot = (Resolve-Path -LiteralPath $DocsRoot).Path
$target = Join-Path $DocsRoot 'MANIFEST.json'
$manifest = Get-Content -LiteralPath $target -Raw | ConvertFrom-Json
$old = @{}
foreach ($entry in $manifest.files) { $old[$entry.path] = $entry }
$entries = @(Get-ChildItem -LiteralPath $DocsRoot -Recurse -File | Where-Object { $_.FullName -ne $target } | Sort-Object FullName | ForEach-Object {
    $relative = [IO.Path]::GetRelativePath($DocsRoot, $_.FullName).Replace('\','/')
    $source = if ($old.ContainsKey($relative)) { $old[$relative].source } else { 'maintained-in-fork' }
    [ordered]@{ path=$relative; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); source=$source }
})
$manifest.files = $entries
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $target -Encoding utf8
Write-Output "Updated $($entries.Count) file hashes; manifest itself excluded"
