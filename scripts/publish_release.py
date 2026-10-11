#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发布一个版本：出正式包 -> 核对版本号 -> 生成中文更新说明和 SHA256 -> 挂 GitHub Release。

App 里的「检查更新」认的就是这一套：
  Jiaoban-v<版本>-release-notes.json   版本号 + 中文更新说明（App 也内置同一份）
  Jiaoban-v<版本>-universal.apk        安装包
  Jiaoban-v<版本>-SHA256.txt           校验值

用法：
    python scripts/publish_release.py            # 构建 + 发布
    python scripts/publish_release.py --no-build # 用现成 APK
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
NOTES = ROOT / "app" / "src" / "main" / "assets" / "release-notes.json"
GRADLE = ROOT / "gradlew.bat"
REPO = "h516df/Jiaoban"
PREFIX = "Jiaoban"
DIST = ROOT / "dist"

HEADINGS = ["新增", "改进", "修复", "说明"]


def run(argv, timeout=2400):
    proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    out = proc.stdout.decode("utf-8", "replace")
    if proc.returncode != 0:
        print(out[-3000:], flush=True)
        raise SystemExit("命令失败：" + " ".join(str(a) for a in argv))
    return out


def version_from_gradle():
    text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    name = re.search(r'versionName = "([^"]+)"', text)
    code = re.search(r"versionCode = (\d+)", text)
    if not name or not code:
        raise SystemExit("在 app/build.gradle.kts 里找不到 versionName/versionCode")
    return name.group(1), int(code.group(1))


def markdown(notes, sha):
    lines = [f"## 交办 v{notes['versionName']}", "", "**覆盖安装即可，旧记录保留。**", ""]
    for heading in HEADINGS:
        items = notes.get(heading) or []
        if not items:
            continue
        lines.append(f"### {heading}")
        lines.extend(f"- {item}" for item in items)
        lines.append("")
    lines.append(f"SHA256: `{sha}`")
    lines.append("")
    lines.append("数据只存在手机本地，不联网、不注册；只有点「检查更新」时才联网。")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-build", action="store_true", help="用现成 APK，不重新构建")
    parser.add_argument("--dry-run", action="store_true", help="只准备文件，不发布")
    args = parser.parse_args()

    version, code = version_from_gradle()
    notes = json.loads(NOTES.read_text(encoding="utf-8"))
    if notes["versionName"] != version or int(notes["versionCode"]) != code:
        raise SystemExit(
            "更新说明和代码里的版本号不一致：build.gradle.kts 是 %s(%d)，"
            "assets/release-notes.json 是 %s(%s)" % (version, code, notes["versionName"], notes["versionCode"])
        )

    if not args.no_build:
        print("== 出正式包 ==", flush=True)
        run([str(GRADLE), "-p", str(ROOT), ":app:assembleRelease", "--console=plain"])
    if not APK.is_file():
        raise SystemExit("没有正式包：" + str(APK))

    sha = hashlib.sha256(APK.read_bytes()).hexdigest()
    out = DIST / f"v{version}"
    out.mkdir(parents=True, exist_ok=True)
    apk_out = out / f"{PREFIX}-v{version}-universal.apk"
    notes_out = out / f"{PREFIX}-v{version}-release-notes.json"
    sha_out = out / f"{PREFIX}-v{version}-SHA256.txt"
    shutil.copyfile(APK, apk_out)
    shutil.copyfile(NOTES, notes_out)
    sha_out.write_text(f"{sha}  app-release.apk\n", encoding="utf-8")

    print("== 发布材料 ==", flush=True)
    for f in (apk_out, notes_out, sha_out):
        print("  %s  %d bytes" % (f.name, f.stat().st_size), flush=True)
    print("  sha256=%s" % sha, flush=True)

    if args.dry_run:
        print("（dry-run，没有发布）", flush=True)
        return 0

    title = "交办 v%s｜%s" % (version, (notes.get("新增") or ["小改动"])[0][:24])
    body = markdown(notes, sha)
    existing = subprocess.run(
        ["gh", "release", "view", f"v{version}", "--repo", REPO],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0
    if existing:
        print("== 这个 tag 已经有发布版，覆盖资产和说明 ==", flush=True)
        run(["gh", "release", "upload", f"v{version}", str(apk_out), str(notes_out), str(sha_out),
             "--repo", REPO, "--clobber"])
        run(["gh", "release", "edit", f"v{version}", "--repo", REPO, "--title", title, "--notes", body])
    else:
        run(["gh", "release", "create", f"v{version}", str(apk_out), str(notes_out), str(sha_out),
             "--repo", REPO, "--title", title, "--notes", body])
    print(run(["gh", "release", "view", f"v{version}", "--repo", REPO,
               "--json", "tagName,url,assets", "--jq",
               '"tag=\\(.tagName) assets=\\([.assets[].name] | join(", ")) \\(.url)"']).strip(), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
