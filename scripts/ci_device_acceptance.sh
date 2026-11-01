#!/usr/bin/env bash
set -euo pipefail

source .cloud/env.sh
bash ./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p app/build/outputs/v024-device
set +e
adb shell am instrument -w cn.jiaoban.test/cn.jiaoban.V024RegressionProbe 2>&1 | tee app/build/outputs/v024-device/run.log
result=${PIPESTATUS[0]}
set -e
adb pull /sdcard/Android/data/cn.jiaoban/files/v024-qa/. app/build/outputs/v024-device/ || true
test "$result" -eq 0
rg -q 'V024_PASS' app/build/outputs/v024-device/run.log
rg -q '"passed": true' app/build/outputs/v024-device/results.json
