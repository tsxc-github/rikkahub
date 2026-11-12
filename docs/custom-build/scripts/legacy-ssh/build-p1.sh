#!/usr/bin/env bash
set -euo pipefail
BASE=/mnt/coding/rikkahub-build
SRC="$BASE/src/rikkahub-ssh-p0"
LOGDIR="$BASE/logs/ssh-p1"
mkdir -p "$LOGDIR"
trap 'rc=$?; echo "$rc" > "$LOGDIR/exit-code"; echo SSH_P1_BUILD_EXIT=$rc' EXIT
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$BASE/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$BASE/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$SRC"
test "$(git branch --show-current)" = ssh-p1
FEATURE=(
 app/src/main/java/me/rerere/rikkahub/ssh
 app/src/main/java/me/rerere/rikkahub/service/SshDevService.kt
 app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSshPage.kt
 app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt
 app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
 app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
 app/src/test/java/me/rerere/rikkahub/ssh
 app/src/github/AndroidManifest.xml
)
git add "${FEATURE[@]}"
run_stage() {
 local name=$1; shift
 echo "START $name"
 local rc=0
 ./gradlew "$@" --console=plain > "$LOGDIR/$name.log" 2>&1 || rc=$?
 echo "$rc" > "$LOGDIR/$name-exit-code"
 echo "END $name exit=$rc"
 return "$rc"
}
format_apply_rc=0; run_stage format-apply spotlessApply || format_apply_rc=$?
# Restore only unrelated formatting introduced by Spotless, not feature work or wrapper environment.
python3 - <<'PY'
import subprocess
allowed_prefixes=('app/src/main/java/me/rerere/rikkahub/ssh/','app/src/test/java/me/rerere/rikkahub/ssh/')
allowed={
'app/src/main/java/me/rerere/rikkahub/service/SshDevService.kt',
'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSshPage.kt',
'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt',
'app/src/main/java/me/rerere/rikkahub/RouteActivity.kt',
'app/src/main/java/me/rerere/rikkahub/di/AppModule.kt',
'app/src/github/AndroidManifest.xml',
'gradle/wrapper/gradle-wrapper.properties'}
paths=subprocess.check_output(['git','diff','--name-only'],text=True).splitlines()
restore=[p for p in paths if p not in allowed and not p.startswith(allowed_prefixes)]
if restore:
    print('Restore unrelated formatter changes:',restore)
    subprocess.run(['git','restore','--source=HEAD','--worktree','--',*restore],check=True)
PY
git add "${FEATURE[@]}"
git diff --cached --check
git commit -m 'feat(ssh): add opt-in loopback development bridge, independent lifecycle and settings'
git rev-parse HEAD > "$LOGDIR/commit"
git format-patch --stdout 7f784084..HEAD > "$LOGDIR/ssh-p1.mbox"
python3 "$BASE/verify-p1-core.py" > "$LOGDIR/core-formatted.log" 2>&1
run_stage assemble :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest
unit_rc=0; run_stage unit :app:testGithubDebugUnitTest :workspace:testDebugUnitTest || unit_rc=$?
lint_rc=0; run_stage lint :app:lintGithubDebug || lint_rc=$?
format_rc=0; run_stage format spotlessCheck || format_rc=$?
find app/build/outputs/apk -type f -name '*.apk' -exec sha256sum {} \; > "$LOGDIR/apk-sha256.txt"
printf 'format-apply=%s assemble=0 unit=%s lint=%s format=%s\n' "$format_apply_rc" "$unit_rc" "$lint_rc" "$format_rc" | tee "$LOGDIR/summary.txt"
if (( unit_rc || lint_rc || format_rc )); then exit 1; fi
