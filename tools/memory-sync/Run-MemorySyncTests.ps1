#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$RepoRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$BuildHome,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$GradleHome = $env:GRADLE_USER_HOME,
    [string]$LogRoot,
    [string]$Python,
    [switch]$Device,
    [switch]$InstallTestApp,
    [switch]$RequireDeviceSsh,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$started = [DateTimeOffset]::UtcNow
if (!$LogRoot) { $LogRoot = Join-Path ([IO.Path]::GetTempPath()) 'rikkahub-memory-sync-tests' }
$runId = $started.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$run = [IO.Path]::GetFullPath((Join-Path $LogRoot $runId))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$report = [ordered]@{
    schemaVersion = 1; startedAt = $started.ToString('o'); completedAt = $null
    repoRoot = $RepoRoot; platform = [Runtime.InteropServices.RuntimeInformation]::OSDescription
    mode = 'HOST'; webUi = 'INCLUDED'; device = [ordered]@{ status = 'NOT_RUN'; reason = 'Use -Device to opt in'; serial = $null; ssh = @{ status = 'NOT_RUN'; reason = 'Requires Linux and -Device -InstallTestApp' } }
    stages = [Collections.Generic.List[object]]::new(); artifacts = [ordered]@{}; exitCode = $null
}
function Save-Report {
    $report | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $run 'result.json') -Encoding utf8
}
function Invoke-Stage([string]$Name, [scriptblock]$Action) {
    $entry = [ordered]@{ name = $Name; status = 'RUNNING'; startedAt = [DateTimeOffset]::UtcNow.ToString('o'); completedAt = $null; exitCode = $null; log = "$Name.log"; error = $null }
    $report.stages.Add($entry)
    Save-Report
    $script:stageLog = Join-Path $run "$Name.log"
    try {
        & $Action *>&1 | Tee-Object -FilePath $script:stageLog | Out-Host
        $entry.exitCode = 0; $entry.status = 'PASS'
    } catch {
        $entry.exitCode = if ($_.Exception.Data.Contains('ExitCode')) { $_.Exception.Data['ExitCode'] } else { 1 }
        $entry.status = 'FAIL'; $entry.error = $_.Exception.Message
        $_ | Out-String | Add-Content -LiteralPath $script:stageLog
        Write-Warning "$Name failed: $($_.Exception.Message)"
    } finally {
        $entry.completedAt = [DateTimeOffset]::UtcNow.ToString('o')
        [IO.File]::WriteAllText((Join-Path $run "$Name-exit-code"), [string]$entry.exitCode)
        Save-Report
    }
}
function Invoke-Native([string]$File, [string[]]$Arguments) {
    & $File @Arguments 2>&1 | ForEach-Object { "$($_)" }
    $rc = $LASTEXITCODE
    if ($rc -ne 0) {
        $failure = [Exception]::new("$([IO.Path]::GetFileName($File)) exited $rc")
        $failure.Data['ExitCode'] = $rc
        throw $failure
    }
}
function Require-Command([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (!$command) { throw "Missing prerequisite: $Name. Install/configure it manually; no automatic installation is performed." }
    return $command.Source
}
function Find-SdkTool([string]$Name) {
    if (!$AndroidHome) { throw 'Supply -AndroidHome or ANDROID_HOME' }
    $leaf = if ($IsWindows) { if ($Name -eq 'apksigner') { "$Name.bat" } else { "$Name.exe" } } else { $Name }
    $dirs = @(Get-ChildItem -LiteralPath (Join-Path $AndroidHome 'build-tools') -Directory | Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending)
    foreach ($dir in $dirs) {
        $candidate = Join-Path $dir.FullName $leaf
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw "Missing Android build-tools tool: $Name"
}
function Read-Apk([string]$Path) {
    $badging = (Invoke-Native $script:aapt @('dump', 'badging', $Path)) -join "`n"
    $package = [regex]::Match($badging, "(?m)^package: name='([^']+)'").Groups[1].Value
    if (!$package) { throw "Cannot read APK package: $Path" }
    $certs = (Invoke-Native $script:apksigner @('verify', '--verbose', '--print-certs', $Path)) -join "`n"
    if ($certs -notmatch '(?m)^Number of signers:\s*1\s*$') { throw 'Expected exactly one verified APK signer' }
    $digest = @([regex]::Matches($certs, '(?im)^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest:\s*([a-f0-9]{64})\s*$') | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Sort-Object -Unique)
    if (@($digest).Count -ne 1) { throw 'Expected exactly one verified APK signing certificate' }
    return @{ package = $package; certificateSha256 = $digest[0]; badging = $badging; path = $Path; sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
}
function Adb([string[]]$Arguments) { Invoke-Native $script:adb (@('-s', $script:deviceSerial) + $Arguments) }
function Save-Screenshot([string]$Destination) {
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:adb; $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    foreach ($arg in @('-s', $script:deviceSerial, 'exec-out', 'screencap', '-p')) { $psi.ArgumentList.Add($arg) }
    $process = [Diagnostics.Process]::Start($psi)
    $errors = $process.StandardError.ReadToEndAsync()
    $stream = [IO.File]::Create($Destination)
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $process.WaitForExit()
    $message = $errors.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "Screenshot failed: $message" }
    $process.Dispose()
    $bytes = [IO.File]::ReadAllBytes($Destination)
    if ($bytes.Length -lt 8 -or [BitConverter]::ToString($bytes, 0, 8) -ne '89-50-4E-47-0D-0A-1A-0A') { throw 'Screenshot did not produce a PNG' }
}
function Save-Evidence([string]$Name) {
    Save-Screenshot (Join-Path $run "$Name.png")
    $remote = "/data/local/tmp/memory-sync-$runId-$Name.xml"
    try {
        Adb @('shell', 'uiautomator', 'dump', $remote)
        Adb @('pull', $remote, (Join-Path $run "$Name.xml"))
    } finally { Adb @('shell', 'rm', '-f', $remote) }
}
function Assert-InstalledSignature([hashtable]$Apk) {
    $listed = (Adb @('shell', 'pm', 'list', 'packages', $Apk.package)) -join "`n"
    if ($listed -notmatch ('(?m)^package:' + [regex]::Escape($Apk.package) + '\s*$')) { return $false }
    $paths = (Adb @('shell', 'pm', 'path', $Apk.package)) -join "`n"
    $base = @([regex]::Matches($paths, '(?m)^package:(.+)') | ForEach-Object { $_.Groups[1].Value.Trim() } | Where-Object { $_ -match '/base\.apk$' })
    if ($base.Count -ne 1) { throw "Cannot identify installed base APK for $($Apk.package)" }
    $local = Join-Path $run ($Apk.package + '-installed.apk')
    try {
        Adb @('pull', $base[0], $local) | Out-Host
        $installed = Read-Apk $local
        if ($installed.package -ne $Apk.package -or $installed.certificateSha256 -ne $Apk.certificateSha256) {
            throw "Installed certificate mismatch for $($Apk.package). Stop; never uninstall or clear data to fix this."
        }
    } finally { if (Test-Path -LiteralPath $local) { Remove-Item -LiteralPath $local } }
    return $true
}
function Collect-BuiltApks {
    if (@($report.stages | Where-Object { $_.status -eq 'FAIL' -and $_.name -in @('assemble-app', 'assemble-instrumentation') }).Count) {
        throw 'APK verification requires successful builds from this run; refusing stale artifacts'
    }
    $script:aapt = Find-SdkTool 'aapt2'; $script:apksigner = Find-SdkTool 'apksigner'
    $metadataFile = Join-Path $RepoRoot 'app/build/outputs/apk/github/debug/output-metadata.json'
    $metadata = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json
    $arm = @($metadata.elements | Where-Object { @($_.filters | Where-Object { $_.filterType -eq 'ABI' -and $_.value -eq 'arm64-v8a' }).Count -eq 1 })
    if ($arm.Count -ne 1) { throw 'Expected exactly one arm64 APK in current Gradle output metadata' }
    $script:appApk = Read-Apk (Join-Path (Split-Path $metadataFile) $arm[0].outputFile)
    $testMetadataFile = Join-Path $RepoRoot 'app/build/outputs/apk/androidTest/github/debug/output-metadata.json'
    $testMetadata = Get-Content -LiteralPath $testMetadataFile -Raw | ConvertFrom-Json
    if (@($testMetadata.elements).Count -ne 1) { throw 'Expected exactly one instrumentation APK' }
    $script:testApk = Read-Apk (Join-Path (Split-Path $testMetadataFile) $testMetadata.elements[0].outputFile)
    if ($appApk.package -ne 'me.rerere.rikkahub.memoryprobe' -or $testApk.package -ne 'me.rerere.rikkahub.memoryprobe.test') { throw 'Refusing APKs outside isolated .memoryprobe packages' }
    if ($appApk.badging -notmatch "native-code:.*'arm64-v8a'") { throw 'App APK does not declare arm64-v8a' }
    if ($appApk.certificateSha256 -ne $testApk.certificateSha256) { throw 'App and instrumentation signatures differ' }
    $manifest = (Invoke-Native $script:aapt @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $testApk.path)) -join "`n"
    $instrumentation = [regex]::Match($manifest, '(?ms)^[ \t]*E: instrumentation\b(.*?)(?=^[ \t]*E:|\z)').Groups[1].Value
    $target = [regex]::Match($instrumentation, ':targetPackage(?:\(0x[0-9a-f]+\))?="([^"]+)"').Groups[1].Value
    if ($target -ne 'me.rerere.rikkahub.memoryprobe') { throw 'Instrumentation APK does not target the isolated package' }
    $report.artifacts.app = @{ path = $appApk.path; sha256 = $appApk.sha256; certificateSha256 = $appApk.certificateSha256; package = $appApk.package }
    $report.artifacts.instrumentation = @{ path = $testApk.path; sha256 = $testApk.sha256; certificateSha256 = $testApk.certificateSha256; package = $testApk.package; targetPackage = $target }
    "Verified isolated arm64 App and instrumentation APKs; matching signatures and SHA-256 recorded."
}
Save-Report
try {
    . (Join-Path $PSScriptRoot 'MemorySyncDeviceFixture.ps1')
    Invoke-Stage 'configuration' {
        $script:RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
        $report.repoRoot = $RepoRoot
        if ($InstallTestApp -and !$Device) { throw '-InstallTestApp requires -Device' }
        if ($Serial -and !$Device) { throw '-Serial requires -Device' }
        if ($RequireDeviceSsh -and (!$Device -or !$InstallTestApp)) { throw '-RequireDeviceSsh requires -Device -InstallTestApp' }
        if ($IsWindows) { throw 'Full host suite requires POSIX (helper flock/fsync and workspace /bin/sh). Run this entire entry with Linux pwsh/JDK/SDK in WSL or on coding, not Windows pwsh with only Python forwarded. Example: wsl --exec pwsh -NoProfile -File /absolute/linux/repo/tools/memory-sync/Run-MemorySyncTests.ps1 -BuildHome /absolute/linux/build-home' }
        if (!(Test-Path -LiteralPath (Join-Path $RepoRoot 'app/build.gradle.kts'))) { throw 'Not a RikkaHub source root' }
        $build = Get-Content -LiteralPath (Join-Path $RepoRoot 'app/build.gradle.kts') -Raw
        if ($build -notmatch 'applicationIdSuffix\s*=\s*"\.memoryprobe"') { throw 'This entry only supports the isolated .memoryprobe debug build' }
        if ($BuildHome) {
            if (!$JavaHome -and (Test-Path -LiteralPath '/usr/lib/jvm/java-21-openjdk-amd64')) { $script:JavaHome = '/usr/lib/jvm/java-21-openjdk-amd64' }
            if (!$AndroidHome) { $script:AndroidHome = Join-Path $BuildHome 'android-sdk' }
            if (!$GradleHome) { $script:GradleHome = Join-Path $BuildHome 'gradle-home' }
        }
        if ($JavaHome) { $env:JAVA_HOME = $JavaHome; $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH }
        if ($AndroidHome) { $env:ANDROID_HOME = $AndroidHome; $env:ANDROID_SDK_ROOT = $AndroidHome; $env:PATH = (Join-Path $AndroidHome 'platform-tools') + [IO.Path]::PathSeparator + $env:PATH }
        if ($GradleHome) { $env:GRADLE_USER_HOME = $GradleHome }
        $script:wrapper = Join-Path $RepoRoot $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
        if (!$Python) { $script:Python = if (Get-Command python3 -ErrorAction SilentlyContinue) { 'python3' } else { 'python' } }
        "Repository: $RepoRoot"
        'No source commits, dependency installation, uninstall, data clearing, or official-package operations.'
    }
    if (@($report.stages | Where-Object { $_.name -eq 'configuration' -and $_.status -eq 'FAIL' }).Count) { throw 'Unsafe or incomplete configuration; no build/device actions were started' }
    Push-Location $RepoRoot
    try {
        Invoke-Stage 'python-tests' {
            $null = Require-Command $Python
            $tests = (Invoke-Native $Python @('-m', 'unittest', 'discover', '-s', 'tools/memory-sync', '-p', 'test_*.py', '-v')) -join "`n"
            $tests
            if ($tests -notmatch 'Ran [1-9][0-9]* tests?') { throw 'Python discovery did not run any tests' }
        }
        Invoke-Stage 'android-prerequisites' {
            $null = Require-Command 'java'
            $version = (Invoke-Native 'java' @('-version')) -join "`n"
            if ($version -notmatch 'version "21(?:\.|"|-)') { throw 'JDK 21 is required' }
            if (!$AndroidHome -or !(Test-Path -LiteralPath $AndroidHome)) { throw 'Supply existing SDK with -AndroidHome or ANDROID_HOME' }
            $null = Require-Command 'pnpm'
            $pnpm = (Invoke-Native 'pnpm' @('--version')) -join ''
            if ($pnpm -notmatch '^10\.') { throw 'pnpm 10 is required' }
            if (!(Test-Path -LiteralPath 'web-ui/node_modules')) { throw 'Missing web-ui/node_modules; prepare dependencies manually before testing' }
            if (!(Test-Path -LiteralPath 'app/google-services.json')) { throw 'Missing local app/google-services.json; see docs/custom-build/BUILD.md (never commit real config)' }
        }
        Invoke-Stage 'app-memory-unit' {
            Invoke-Native $wrapper @(':app:testGithubDebugUnitTest', '--tests', '*Memory*', '--console=plain')
        }
        Invoke-Stage 'workspace-unit' { Invoke-Native $wrapper @(':workspace:testDebugUnitTest', '--console=plain') }
        Invoke-Stage 'assemble-app' { Invoke-Native $wrapper @(':app:assembleGithubDebug', '--console=plain') }
        Invoke-Stage 'assemble-instrumentation' { Invoke-Native $wrapper @(':app:assembleGithubDebugAndroidTest', '--console=plain') }
        Invoke-Stage 'apk-verification' { Collect-BuiltApks }
        if ($Device) {
            $report.mode = 'HOST_AND_DEVICE'; $report.device.status = 'RUNNING'; $report.device.reason = $null
            Invoke-Stage 'device-preflight' {
                if (@($report.stages | Where-Object { $_.status -eq 'FAIL' -and $_.name -in @('configuration', 'assemble-app', 'assemble-instrumentation', 'apk-verification') }).Count) { throw 'Device checks require successful isolated builds and APK verification from this run' }
                $script:adb = Require-Command 'adb'
                $devices = (Invoke-Native $adb @('devices')) -join "`n"
                $ready = @([regex]::Matches($devices, '(?m)^([^\s]+)\s+device\s*$') | ForEach-Object { $_.Groups[1].Value })
                if ($Serial) {
                    if ($Serial -notin $ready) { throw 'Selected serial is not an authorized online device' }
                    $script:deviceSerial = $Serial
                } else {
                    if ($ready.Count -ne 1) { throw 'Exactly one authorized online device is required, or select -Serial explicitly' }
                    $script:deviceSerial = $ready[0]
                }
                $report.device.serial = $deviceSerial
                $abis = ((Adb @('shell', 'getprop', 'ro.product.cpu.abilist')) -join '').Trim().Split(',')
                if ('arm64-v8a' -notin $abis) { throw 'Memory probe device must support arm64-v8a' }
                $script:appInstalled = Assert-InstalledSignature $appApk
                $script:testInstalled = Assert-InstalledSignature $testApk
                if (!$InstallTestApp -and (!$appInstalled -or !$testInstalled)) { throw 'Isolated APKs are not installed; use -Device -InstallTestApp to explicitly authorize install' }
                if (!$InstallTestApp) {
                    foreach ($apk in @($appApk, $testApk)) {
                        $remote = ((Adb @('shell', 'pm', 'path', $apk.package)) -join "`n")
                        $basePath = [regex]::Match($remote, '(?m)^package:(.+/base\.apk)').Groups[1].Value.Trim()
                        $installedHash = ((Adb @('shell', 'sha256sum', $basePath)) -join '').Split(' ')[0]
                        if ($installedHash -ne $apk.sha256) { throw 'Installed APK differs from current build; explicitly authorize -InstallTestApp' }
                    }
                }
            }
            $preflight = $report.stages | Where-Object { $_.name -eq 'device-preflight' }
            if ($preflight.status -eq 'PASS') {
                if ($InstallTestApp) {
                    Invoke-Stage 'device-install' {
                        Adb @('install', '-r', '-t', $appApk.path)
                        Adb @('install', '-r', '-t', $testApk.path)
                    }
                }
                if (!@($report.stages | Where-Object { $_.name -eq 'device-install' -and $_.status -eq 'FAIL' }).Count) {
                    $hasSshd = (Get-Command 'sshd' -ErrorAction SilentlyContinue) -or (Test-Path -LiteralPath '/usr/sbin/sshd')
                    if ($InstallTestApp -and $IsLinux -and $hasSshd) {
                        $report.device.ssh = @{ status = 'RUNNING'; reason = $null }
                        Invoke-Stage 'device-ssh-provision' { Start-MemorySyncDeviceFixture }
                        if ($report.device.ssh.status -ne 'READY') { $report.device.ssh.status = 'FAIL' }
                    } else {
                        $reason = if (!$InstallTestApp) { 'No permission to provision fixture; add -InstallTestApp' } elseif (!$IsLinux) { 'Ephemeral SSH fixture currently requires Linux' } else { 'Existing OpenSSH sshd binary is missing; no auto-install' }
                        $report.device.ssh = @{ status = 'NOT_RUN'; reason = $reason }
                        $report.stages.Add([ordered]@{ name = 'device-ssh-provision'; status = 'NOT_RUN'; exitCode = $null; error = $reason })
                        if ($RequireDeviceSsh) { Invoke-Stage 'device-ssh-required' { throw $reason } }
                    }
                    Invoke-Stage 'device-instrumentation' {
                        $sshExpected = if ($report.device.ssh.status -eq 'READY') { 'true' } else { 'false' }
                        $instrumentation = (Adb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'me.rerere.rikkahub.MemorySyncDeviceTest', '-e', 'memorySyncRunId', $runId, '-e', 'memorySyncSshExpected', $sshExpected, 'me.rerere.rikkahub.memoryprobe.test/androidx.test.runner.AndroidJUnitRunner')) -join "`n"
                        $instrumentation
                        if ($instrumentation -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed' -or $instrumentation -notmatch 'OK \([1-9][0-9]* tests?\)') { throw 'Instrumentation did not report successful nonempty MemorySyncDeviceTest execution' }
                    }
                    if ($report.device.ssh.status -eq 'READY') {
                        Invoke-Stage 'device-ssh-host-verification' { Assert-MemorySyncDeviceFixtureResult }
                        if ($report.device.ssh.status -ne 'PASS') { $report.device.ssh.status = 'FAIL' }
                    }
                    Invoke-Stage 'device-ui-evidence' {
                        $destination = Join-Path $run 'device-evidence'
                        New-Item -ItemType Directory -Path $destination | Out-Null
                        Adb @('pull', "/sdcard/Android/data/me.rerere.rikkahub.memoryprobe/files/memory-sync-tests/$runId/.", $destination)
                        foreach ($checkpoint in @('global', 'assistant', 'import', 'import-result', 'export', 'conflict')) {
                            foreach ($extension in @('png', 'xml')) {
                                if (!(Test-Path -LiteralPath (Join-Path $destination "$checkpoint.$extension"))) { throw "Missing instrumentation UI evidence: $checkpoint.$extension" }
                            }
                        }
                        if ($report.device.ssh.status -eq 'PASS') {
                            foreach ($checkpoint in @('sync-settings', 'sync-conflict')) {
                                foreach ($extension in @('png', 'xml')) {
                                    if (!(Test-Path -LiteralPath (Join-Path $destination "$checkpoint.$extension"))) { throw "Missing real SSH UI evidence: $checkpoint.$extension" }
                                }
                            }
                        }
                    }
                    if (@($report.stages | Where-Object { $_.name -like 'device-*' -and $_.status -eq 'FAIL' }).Count) {
                        Invoke-Stage 'device-failure-evidence' { Save-Evidence 'failure' }
                    }
                }
            }
            $report.device.status = if (@($report.stages | Where-Object { $_.name -like 'device-*' -and $_.status -eq 'FAIL' }).Count) { 'FAIL' } else { 'PASS' }
        }
    } finally { Pop-Location }
} catch {
    $report.stages.Add([ordered]@{ name = 'driver'; status = 'FAIL'; exitCode = 1; error = $_.Exception.Message })
    Write-Warning $_.Exception.Message
} finally {
    if ($script:fixtureDirectory) { Invoke-Stage 'device-ssh-cleanup' { Stop-MemorySyncDeviceFixture } }
    if ($Device -and $report.device.status -eq 'RUNNING') { $report.device.status = 'FAIL'; $report.device.reason = 'Driver did not complete' }
    if ($Device -and @($report.stages | Where-Object { $_.name -like 'device-*' -and $_.status -eq 'FAIL' }).Count) { $report.device.status = 'FAIL' }
    $report.completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $report.exitCode = if (@($report.stages | Where-Object { $_.status -eq 'FAIL' }).Count) { 1 } else { 0 }
    Save-Report
    [IO.File]::WriteAllText((Join-Path $run 'exit-code'), [string]$report.exitCode)
    Write-Output "MEMORY_SYNC_TEST_EXIT=$($report.exitCode) report=$(Join-Path $run 'result.json') device=$($report.device.status)"
}
exit $report.exitCode
