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
import zipfile
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

def find_release(tag):
    """Tag lookup can hide an unpublished draft; list authenticated drafts before creating."""
    query = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{tag}"], capture_output=True, timeout=120)
    if query.returncode == 0:
        return json.loads(query.stdout)
    problem = query.stderr.decode("utf-8", "replace")
    if "HTTP 404" not in problem:
        raise SystemExit("无法核对已有发布，停止而不猜测状态：" + problem[:300])
    pages = json.loads(run(["gh", "api", f"repos/{REPO}/releases?per_page=100", "--paginate", "--slurp"]))
    matches = [release for page in pages for release in page if release["tag_name"] == tag]
    if len(matches) > 1:
        raise SystemExit("同一版本存在多个发布记录，保留现场并停止。")
    return matches[0] if matches else None


def markdown(notes, sha):
    lines = [f"## 拾页 v{notes['versionName']}", "", "**覆盖安装即可，旧记录保留。**", ""]
    for heading in HEADINGS:
        items = notes.get(heading) or []
        if not items:
            continue
        lines.append(f"### {heading}")
        lines.extend(f"- {item}" for item in items)
        lines.append("")
    lines.append(f"SHA256: `{sha}`")
    lines.append("")
    lines.append("应用使用本地记录，不设账号体系；只有主动检查更新时由应用联网。系统云备份与换机行为取决于手机设置，备份策略仍在 Issue #5 待确认。")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-build", action="store_true", help="用现成 APK，不重新构建")
    parser.add_argument("--dry-run", action="store_true", help="只准备文件，不发布")
    parser.add_argument("--target-commit", default="", help="核对过的完整发布目标 SHA")
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

    with zipfile.ZipFile(APK) as archive:
        embedded_notes = json.loads(archive.read("assets/release-notes.json"))
    if embedded_notes != notes:
        raise SystemExit("安装包里的更新说明与发布材料不一致，请重新构建。")
    sha = hashlib.sha256(APK.read_bytes()).hexdigest()
    out = DIST / f"v{version}"
    out.mkdir(parents=True, exist_ok=True)
    apk_out = out / f"{PREFIX}-v{version}-universal.apk"
    notes_out = out / f"{PREFIX}-v{version}-release-notes.json"
    sha_out = out / f"{PREFIX}-v{version}-SHA256.txt"
    shutil.copyfile(APK, apk_out)
    shutil.copyfile(NOTES, notes_out)
    sha_out.write_text(f"{sha}  {apk_out.name}\n", encoding="utf-8")

    print("== 发布材料 ==", flush=True)
    for f in (apk_out, notes_out, sha_out):
        print("  %s  %d bytes" % (f.name, f.stat().st_size), flush=True)
    print("  sha256=%s" % sha, flush=True)

    if args.dry_run:
        print("（dry-run，没有发布）", flush=True)
        return 0

    # Private-project publication is authorized in this task. All uploads are resumable and verified.
    private = json.loads(run(["gh", "api", f"repos/{REPO}"]))["private"]
    if not private:
        raise SystemExit("仓库不是私有，停止发布。")
    from android_redesign_acceptance import fingerprint
    evidence_path = ROOT / "verification" / "redesign-release" / "bound-results.json"
    if not evidence_path.is_file():
        raise SystemExit("缺少正式包真实验收证据，停止发布。")
    evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
    if not evidence.get("passed") or evidence["binding"]["apkSha256"] != sha or evidence["binding"]["sourceFingerprint"] != fingerprint():
        raise SystemExit("正式包、当前源码与已通过证据不一致，停止发布。")
    head = run(["git", "rev-parse", "HEAD"]).strip()
    tag = f"v{version}"
    target = args.target_commit or head
    if not re.fullmatch(r"[0-9a-f]{40}", target):
        raise SystemExit("发布目标必须是完整提交 SHA。")
    title = "拾页 v%s｜%s" % (version, (notes.get("新增") or ["小改动"])[0][:24])
    body_file = out / "release-body.md"
    body_file.write_text(markdown(notes, sha), encoding="utf-8")
    meta = find_release(tag)
    if meta is None:
        run(["gh", "release", "create", tag, "--repo", REPO, "--target", target,
             "--draft", "--title", title, "--notes-file", str(body_file)])
        meta = find_release(tag)
        if meta is None:
            raise SystemExit("新草稿尚不可读取，保留现场并停止，不重复创建。")
    endpoint = f"repos/{REPO}/releases/{meta['id']}"
    files = {p.name: p for p in (apk_out, notes_out, sha_out)}
    if meta["draft"] and meta["target_commitish"] != target:
        raise SystemExit("原草稿目标提交不一致，保留原草稿并停止。")
    import uuid
    readback = out / ("readback-" + uuid.uuid4().hex[:12])
    readback.mkdir()
    def verify_uploaded(release):
        found = {}
        for asset in release["assets"]:
            name = asset["name"]
            if name not in files:
                raise SystemExit("发布中有未知资产，保留现场并停止：" + name)
            local = files[name]
            if asset["size"] != local.stat().st_size:
                raise SystemExit("已有资产大小不一致，不覆盖：" + name)
            run(["gh", "release", "download", tag, "--repo", REPO, "--pattern", name, "--dir", str(readback)])
            actual = hashlib.sha256((readback / name).read_bytes()).hexdigest()
            expected = hashlib.sha256(local.read_bytes()).hexdigest()
            if actual != expected:
                raise SystemExit("已有资产内容不一致，不覆盖：" + name)
            found[name] = {"id": asset["id"], "name": name, "size": asset["size"], "sha256": actual}
        return found
    found = verify_uploaded(meta)
    if not meta["draft"]:
        if set(found) != set(files):
            raise SystemExit("已发布版本资产不完整，只读核对后停止，不修改已有发布。")
        print("该版本已发布，资产回读一致；未修改已有发布。", flush=True)
    else:
        missing = [p for name, p in files.items() if name not in found]
        for path in missing:
            run(["gh", "release", "upload", tag, str(path), "--repo", REPO])
        meta = json.loads(run(["gh", "api", endpoint]))
        # Only download newly added assets; previously verified files remain bound to their ids/size.
        for asset in meta["assets"]:
            if asset["name"] in found:
                saved = readback / asset["name"]
                if asset["size"] != saved.stat().st_size or asset["id"] != found[asset["name"]]["id"]:
                    raise SystemExit("已核对资产在续传中发生变化，停止。")
            else:
                single = dict(meta); single["assets"] = [asset]
                found.update(verify_uploaded(single))
        if set(found) != set(files):
            raise SystemExit("发布材料没有完整回读，保留草稿并停止。")
        run(["gh", "release", "edit", tag, "--repo", REPO, "--draft=false"])
        meta = json.loads(run(["gh", "api", endpoint]))
        if meta["draft"]:
            raise SystemExit("发布仍为草稿，保留现场。")
    proof = {"privateRepository": True, "tag": tag, "url": meta["html_url"],
             "targetCommit": meta["target_commitish"], "sourceFingerprint": fingerprint(),
             "draft": meta["draft"], "readBackVerified": True, "assets": list(found.values())}
    (out / "published-results.json").write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(proof, ensure_ascii=False, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
