#!/usr/bin/env bash
set -euo pipefail

repo='h516df/jiyixia'
dir='app/build/outputs/signed-upgrade'
mkdir -p "$dir/old" "$dir/new"
gh release download v0.23 --repo "$repo" --pattern 'Jiaoban-v0.23-universal.apk' --dir "$dir/old"
gh release download v0.24 --repo "$repo" --pattern 'Jiyixia-v0.24-universal.apk' --dir "$dir/new"
gh release download v0.24 --repo "$repo" --pattern 'Jiaoban-v0.24-SHA256.txt' --dir "$dir/new"
old="$dir/old/Jiaoban-v0.23-universal.apk"
new="$dir/new/Jiyixia-v0.24-universal.apk"
test "$(sha256sum "$old" | cut -d' ' -f1)" = '56bbced8d10a55ad548b2c22f741bb1baba9e7f3861989cdd601e2cad3c024be'
expected="$(head -n1 "$dir/new/Jiaoban-v0.24-SHA256.txt" | cut -d' ' -f1)"
test "$(sha256sum "$new" | cut -d' ' -f1)" = "$expected"
bt="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools/35.0.0"
for apk in "$old" "$new"; do
  "$bt/apksigner" verify --print-certs "$apk" > "$apk.signature.txt"
  grep -qi '21b74e3e2a10e12da8284d4e6bd7d99e55b00a385215902e94d7896f7a46c967' "$apk.signature.txt"
  "$bt/aapt" dump badging "$apk" > "$apk.badging.txt"
  grep -q "package: name='cn.jiaoban'" "$apk.badging.txt"
done
grep -q "versionCode='23'" "$old.badging.txt"
grep -q "versionCode='24' versionName='0.24'" "$new.badging.txt"
echo 'PASS: both official APK files share the original signing certificate, package and expected versions'
