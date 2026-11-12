#requires -Version 7.0
# coding-local build/test orchestration. Only .ps1 invokes new test runs.
[CmdletBinding()]
param([ValidateSet('Core','Full')][string]$Stage = 'Full')
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$Base = '/mnt/coding/rikkahub-build'
$Repo = "$Base/src/rikkahub-ssh-p0"
$Logs = "$Base/logs/ssh-lan"
New-Item -ItemType Directory -Force -Path $Logs | Out-Null
$env:JAVA_HOME = '/usr/lib/jvm/java-21-openjdk-amd64'
$env:ANDROID_HOME = "$Base/android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$Base/gradle-home"
$env:PATH = "$env:JAVA_HOME/bin:$env:ANDROID_HOME/platform-tools:" + $env:PATH
$Java = "$env:JAVA_HOME/bin/java"
$Cache = "$Base/gradle-home/caches/modules-2/files-2.1"
function Get-CachedJar([string]$Group,[string]$Artifact,[string]$Version) {
    $files = @(Get-ChildItem -LiteralPath "$Cache/$Group/$Artifact/$Version" -Recurse -File -Filter '*.jar' | Sort-Object FullName)
    if ($files.Count -eq 0) { throw "Missing cached jar: $Group/$Artifact/$Version" }
    return $files[-1].FullName
}
function Invoke-CoreTests {
    $compiler = Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.4.21'
    $jars = @(
        (Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.4.21'),
        (Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.4.21'),
        (Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '2.3.21'),
        (Get-CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.11.0'),
        (Get-CachedJar 'org.jetbrains' 'annotations' '23.0.0'),
        (Get-CachedJar 'junit' 'junit' '4.13.2'),
        (Get-CachedJar 'org.hamcrest' 'hamcrest-core' '1.3')
    )
    $cp = $jars -join [IO.Path]::PathSeparator
    $output = "$Logs/core-classes"
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $sources = @(
        "$Repo/app/src/main/java/me/rerere/rikkahub/ssh/SshDevConfig.kt",
        "$Repo/app/src/main/java/me/rerere/rikkahub/ssh/SshDevController.kt",
        "$Repo/app/src/test/java/me/rerere/rikkahub/ssh/SshDevTest.kt",
        "$Repo/app/src/test/java/me/rerere/rikkahub/ssh/SshLanPolicyTest.kt"
    )
    $compilerArgs = @('-cp', ($compiler + [IO.Path]::PathSeparator + $cp), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', $cp, '-d', $output) + $sources
    & $Java @compilerArgs
    if ($LASTEXITCODE -ne 0) { throw 'Actual Kotlin core compilation failed' }
    & $Java -cp ($output + [IO.Path]::PathSeparator + $cp) org.junit.runner.JUnitCore me.rerere.rikkahub.ssh.SshDevConfigTest me.rerere.rikkahub.ssh.SshDevControllerTest me.rerere.rikkahub.ssh.SshLanPolicyTest
    if ($LASTEXITCODE -ne 0) { throw 'Actual Kotlin core regression failed' }
}
function Invoke-GradleStage([string]$Name,[string[]]$GradleArguments) {
    Write-Host "START $Name"
    & ./gradlew @GradleArguments --console=plain *> "$Logs/$Name.log"
    $rc = $LASTEXITCODE
    Set-Content -LiteralPath "$Logs/$Name-exit-code" -Value $rc -Encoding utf8NoBOM
    Write-Host "END $Name exit=$rc"
    return [int]$rc
}
Set-Location -LiteralPath $Repo
if ($Stage -eq 'Core') { Invoke-CoreTests; exit 0 }
$final = 1
try {
    Invoke-CoreTests *> "$Logs/core-before.log"
    $branch = (& git branch --show-current).Trim()
    if ($branch -ne 'ssh-p1') { throw "Unexpected branch: $branch" }
    $feature = @(
        'app/src/main/java/me/rerere/rikkahub/ssh',
        'app/src/main/java/me/rerere/rikkahub/service/SshDevService.kt',
        'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSshPage.kt',
        'app/src/test/java/me/rerere/rikkahub/ssh',
        'app/src/github/AndroidManifest.xml'
    )
    & git add -- @feature
    if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
    $apply = Invoke-GradleStage -Name 'format-apply' -GradleArguments @('spotlessApply')
    $allowedExact = @(
        'app/src/main/java/me/rerere/rikkahub/service/SshDevService.kt',
        'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSshPage.kt',
        'app/src/github/AndroidManifest.xml',
        'gradle/wrapper/gradle-wrapper.properties'
    )
    $restore = @(& git diff --name-only | Where-Object {
        $_ -notin $allowedExact -and -not $_.StartsWith('app/src/main/java/me/rerere/rikkahub/ssh/') -and -not $_.StartsWith('app/src/test/java/me/rerere/rikkahub/ssh/')
    })
    if ($restore.Count) {
        Write-Host ('Restore unrelated formatting: ' + ($restore -join ', '))
        & git restore --source=HEAD --worktree -- @restore
        if ($LASTEXITCODE -ne 0) { throw 'Unrelated formatter restore failed' }
    }
    & git add -- @feature
    & git diff --cached --check
    if ($LASTEXITCODE -ne 0) { throw 'Staged diff has errors' }
    & git diff --cached --quiet
    if ($LASTEXITCODE -ne 0) {
        & git commit -m 'feat(ssh): add explicit Wi-Fi and phone-hotspot address binding'
        if ($LASTEXITCODE -ne 0) { throw 'Commit failed' }
    }
    & git rev-parse HEAD | Set-Content -LiteralPath "$Logs/commit" -Encoding utf8NoBOM
    & git format-patch --stdout fe1a683c..HEAD | Set-Content -LiteralPath "$Logs/ssh-lan.mbox" -Encoding utf8NoBOM
    Invoke-CoreTests *> "$Logs/core-formatted.log"
    $assemble = Invoke-GradleStage -Name 'assemble' -GradleArguments @(':app:assembleGithubDebug',':app:assembleGithubDebugAndroidTest')
    if ($assemble -ne 0) { throw 'APK build failed; preserve assemble.log' }
    $unit = Invoke-GradleStage -Name 'unit' -GradleArguments @(':app:testGithubDebugUnitTest',':workspace:testDebugUnitTest')
    $lint = Invoke-GradleStage -Name 'lint' -GradleArguments @(':app:lintGithubDebug')
    $format = Invoke-GradleStage -Name 'format' -GradleArguments @('spotlessCheck')
    "format-apply=$apply assemble=$assemble unit=$unit lint=$lint format=$format" | Tee-Object -FilePath "$Logs/summary.txt"
    if ($unit -eq 0 -and $lint -eq 0 -and $format -eq 0) { $final = 0 }
} catch {
    Write-Host ('BUILD ERROR: ' + $_.Exception.Message)
} finally {
    Set-Content -LiteralPath "$Logs/exit-code" -Value $final -Encoding utf8NoBOM
    Write-Host "SSH_LAN_BUILD_EXIT=$final"
}
exit $final
