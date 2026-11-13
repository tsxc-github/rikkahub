#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Inspect','Debug','Release')][string]$Stage = 'Inspect',
    [string]$RepoRoot = (Join-Path $PSScriptRoot '../../..'),
    [string]$BuildHome,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$GradleHome = $env:GRADLE_USER_HOME,
    [string]$LogRoot,
    [switch]$AllowOfficialPackage
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
function GitValue([string[]]$Arguments) {
    $v = & git -C $RepoRoot @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git command failed: $($Arguments -join ' ')" }
    return ($v -join "`n")
}
$head = GitValue -Arguments @('rev-parse','HEAD')
$branch = GitValue -Arguments @('branch','--show-current')
$dirty = GitValue -Arguments @('status','--short')
$buildFile = Join-Path $RepoRoot 'app/build.gradle.kts'
if (!(Test-Path -LiteralPath $buildFile)) { throw 'Not a RikkaHub source root' }
$app = Get-Content -LiteralPath $buildFile -Raw
$version = [regex]::Match($app, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$code = [regex]::Match($app, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$suffix = [regex]::Match($app, 'applicationIdSuffix\s*=\s*"([^"]+)"').Groups[1].Value
if ($suffix -notin @('.ops','.sshprobe','.memoryprobe')) { throw "Unexpected debug suffix '$suffix'; review package policy" }
Write-Output "repo=$RepoRoot branch=$branch head=$head"
Write-Output "version=$version code=$code debugPackage=me.rerere.rikkahub$suffix"
if ($dirty) { Write-Warning "Working tree is dirty; no automatic stash/reset will occur:`n$dirty" }
if ($Stage -eq 'Inspect') { Write-Output 'INSPECT_ONLY: no builds, writes, installs or commits'; exit 0 }
if ($Stage -eq 'Release' -and !$AllowOfficialPackage) {
    throw 'Release uses the official package ID; supply -AllowOfficialPackage after user approval'
}
if ($BuildHome) {
    if (!$JavaHome -and (Test-Path '/usr/lib/jvm/java-21-openjdk-amd64')) { $JavaHome = '/usr/lib/jvm/java-21-openjdk-amd64' }
    if (!$AndroidHome) { $AndroidHome = Join-Path $BuildHome 'android-sdk' }
    if (!$GradleHome) { $GradleHome = Join-Path $BuildHome 'gradle-home' }
    if (!$LogRoot) { $LogRoot = Join-Path $BuildHome 'logs' }
}
if (!$JavaHome -or !(Test-Path -LiteralPath $JavaHome)) { throw 'Supply -JavaHome or JAVA_HOME (JDK 21)' }
if (!$AndroidHome -or !(Test-Path -LiteralPath $AndroidHome)) { throw 'Supply -AndroidHome or ANDROID_HOME' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome
if ($GradleHome) { $env:GRADLE_USER_HOME = $GradleHome }
$env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + (Join-Path $AndroidHome 'platform-tools') + [IO.Path]::PathSeparator + $env:PATH
$javaVersion = (& java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21(?:\.|"|-)') { throw 'JDK 21 is required' }
if (!(Test-Path -LiteralPath (Join-Path $RepoRoot 'app/google-services.json'))) { throw 'Missing local app/google-services.json; see BUILD.md, never commit real config' }
$pnpmVersion = & pnpm --version
if ($LASTEXITCODE -ne 0 -or ($pnpmVersion -join '') -notmatch '^10\.') { throw 'pnpm 10 is required; dependencies are not installed automatically' }
if (!$LogRoot) { $LogRoot = Join-Path ([IO.Path]::GetTempPath()) 'rikkahub-build-logs' }
$run = Join-Path $LogRoot ('handoff-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
New-Item -ItemType Directory -Path $run | Out-Null
$metadata = [ordered]@{ sourceHead=$head; branch=$branch; dirty=$dirty; stage=$Stage; version=$version; versionCode=$code; debugSuffix=$suffix; webUi='included'; results=[ordered]@{} }
$wrapper = if ($IsWindows) { Join-Path $RepoRoot 'gradlew.bat' } else { Join-Path $RepoRoot 'gradlew' }
$stages = [ordered]@{}
if ($Stage -eq 'Debug') { $stages.assemble = @(':app:assembleGithubDebug',':app:assembleGithubDebugAndroidTest') }
else { $stages.assemble = @(':app:assembleGithubRelease') }
$stages.unit = @(':app:testGithubDebugUnitTest',':workspace:testDebugUnitTest')
$stages.lint = @(':app:lintGithubDebug')
$stages.format = @('spotlessCheck')
Push-Location $RepoRoot
try {
    foreach ($name in $stages.Keys) {
        $arguments = $stages[$name] + @('--console=plain')
        & $wrapper @arguments 2>&1 | Tee-Object -FilePath (Join-Path $run "$name.log") | Out-Host
        $rc = $LASTEXITCODE
        $metadata.results[$name] = $rc
        [IO.File]::WriteAllText((Join-Path $run "$name-exit-code"), [string]$rc)
        $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'result.json') -Encoding utf8
    }
} finally { Pop-Location }
$failed = @($metadata.results.Values | Where-Object { $_ -ne 0 })
$overall = if ($failed.Count) { 1 } else { 0 }
[IO.File]::WriteAllText((Join-Path $run 'exit-code'), [string]$overall)
Write-Output "RIKKA_BUILD_EXIT=$overall logs=$run"
exit $overall
