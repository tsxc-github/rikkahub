#!/bin/bash
# Run only on coding. Does not change the active ops-v2 checkout or CT settings.
set -euo pipefail
BASE=/mnt/coding/rikkahub-build
SRC="$BASE/src/rikkahub-ssh-p0"
ORIGINAL="$BASE/src/rikkahub"
LOGDIR="$BASE/logs/ssh-p0"
mkdir -p "$LOGDIR"
trap 'rc=$?; printf "%s\n" "$rc" > "$LOGDIR/exit-code"' EXIT
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$BASE/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$BASE/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$SRC"
git submodule update --init --recursive
# Existing machine-local build configuration remains local/ignored, not exported.
cp "$ORIGINAL/local.properties" local.properties
python3 - "$ORIGINAL" "$SRC" <<'PY'
import json, pathlib, sys
original, target = map(pathlib.Path, sys.argv[1:])
data = json.loads((original / 'app/google-services.json').read_text())
client = data['client'][0]
probe = json.loads(json.dumps(client))
probe['client_info']['android_client_info']['package_name'] = 'me.rerere.rikkahub.sshprobe'
base = json.loads(json.dumps(client))
base['client_info']['android_client_info']['package_name'] = 'me.rerere.rikkahub'
data['client'] = [base, probe]
(target / 'app/google-services.json').write_text(json.dumps(data))
wrapper = target / 'gradle/wrapper/gradle-wrapper.properties'
text = wrapper.read_text()
text = '\n'.join('distributionUrl=https\\://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip' if line.startswith('distributionUrl=') else line for line in text.splitlines()) + '\n'
wrapper.write_text(text)
PY
# Both files above are machine-local and never part of the deliverable patch.
printf '\n/app/google-services.json\n' >> "$(git rev-parse --git-path info/exclude)"
if [ -d "$ORIGINAL/web-ui/node_modules" ] && [ ! -e web-ui/node_modules ]; then
    ln -s "$ORIGINAL/web-ui/node_modules" web-ui/node_modules
fi
git add app/build.gradle.kts app/src/androidTest/java/me/rerere/rikkahub/ssh/SshP0DeviceTest.kt
./gradlew spotlessApply --console=plain > "$LOGDIR/format.log" 2>&1
# Add formatted files only, not wrapper/local properties.
git add app/build.gradle.kts app/src/androidTest/java/me/rerere/rikkahub/ssh/SshP0DeviceTest.kt
git commit -m 'test(ssh): add isolated ADB P0 lifecycle and computer probes'
git format-patch --stdout 068dea4917e534cb3ed186ae963df98e66c084a4..HEAD > "$LOGDIR/ssh-p0.mbox"
git rev-parse HEAD > "$LOGDIR/commit"
./gradlew :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest --console=plain > "$LOGDIR/assemble.log" 2>&1
printf '0\n' > "$LOGDIR/assemble-exit-code"
./gradlew :app:testGithubDebugUnitTest :workspace:testDebugUnitTest --console=plain > "$LOGDIR/unit.log" 2>&1
printf '0\n' > "$LOGDIR/unit-exit-code"
./gradlew :app:lintGithubDebug spotlessCheck --console=plain > "$LOGDIR/check.log" 2>&1
printf '0\n' > "$LOGDIR/check-exit-code"
printf 'DONE\n'
