#!/usr/bin/env bash
# coding only. Rebased P0; no host/CT configuration changes.
set -euo pipefail
BASE=/mnt/coding/rikkahub-build
SRC="$BASE/src/rikkahub-ssh-p0"
ORIGINAL="$BASE/src/rikkahub"
LOGDIR="$BASE/logs/ssh-p0-v312"
mkdir -p "$LOGDIR"
trap 'rc=$?; printf "%s\n" "$rc" > "$LOGDIR/exit-code"; printf "SSH_P0_V312_EXIT=%s\n" "$rc"' EXIT
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$BASE/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$BASE/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$SRC"
git rev-parse HEAD > "$LOGDIR/commit"
git submodule update --init --recursive
# All keys and local configuration stay on coding and outside patches/artifacts.
cp "$ORIGINAL/local.properties" local.properties
python3 - "$ORIGINAL" "$SRC" <<'PY'
import json,pathlib,sys
original,target=map(pathlib.Path,sys.argv[1:])
data=json.loads((original/'app/google-services.json').read_text())
client=data['client'][0]
clients=[]
for package in ('me.rerere.rikkahub','me.rerere.rikkahub.sshprobe'):
    c=json.loads(json.dumps(client))
    c['client_info']['android_client_info']['package_name']=package
    clients.append(c)
data['client']=clients
(target/'app/google-services.json').write_text(json.dumps(data))
PY
printf '\n/app/google-services.json\n/web-ui/node_modules\n' >> "$(git rev-parse --git-path info/exclude)"
if [ -d "$ORIGINAL/web-ui/node_modules" ] && [ ! -e web-ui/node_modules ]; then
    ln -s "$ORIGINAL/web-ui/node_modules" web-ui/node_modules
fi
run_stage() {
    local name=$1
    shift
    echo "START $name"
    local rc=0
    ./gradlew "$@" --console=plain > "$LOGDIR/$name.log" 2>&1 || rc=$?
    printf '%s\n' "$rc" > "$LOGDIR/$name-exit-code"
    echo "END $name exit=$rc"
    return "$rc"
}
run_stage assemble :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest
unit_rc=0; run_stage unit :app:testGithubDebugUnitTest :workspace:testDebugUnitTest || unit_rc=$?
lint_rc=0; run_stage lint :app:lintGithubDebug || lint_rc=$?
format_rc=0; run_stage format spotlessCheck || format_rc=$?
find app/build/outputs/apk -name '*.apk' -type f -exec sha256sum {} \; > "$LOGDIR/apk-sha256.txt"
printf 'assemble=0 unit=%s lint=%s format=%s\n' "$unit_rc" "$lint_rc" "$format_rc" | tee "$LOGDIR/summary.txt"
# Lint/format failures are recorded independently; never mask a failed stage.
if (( unit_rc || lint_rc || format_rc )); then exit 1; fi
