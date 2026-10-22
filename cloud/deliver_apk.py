#!/usr/bin/env python3
"""Store a development APK in a private draft; never publish, delete or replace assets."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
REPO = 'h516df/jiyixia'


def api(endpoint, method='GET', data=None, upload=None, output=None):
    command = ['gh', 'api', endpoint, '--method', method]
    payload = None
    if data is not None:
        command += ['--input', '-']
        payload = json.dumps(data).encode()
    if upload is not None:
        command += ['--input', str(upload), '-H', 'Content-Type: application/octet-stream']
    if output is not None:
        command += ['-H', 'Accept: application/octet-stream']
        with Path(output).open('wb') as stream:
            subprocess.run(command, check=True, stdout=stream)
        return None
    result = subprocess.run(command, input=payload, check=True, stdout=subprocess.PIPE)
    return json.loads(result.stdout)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def require_draft(meta, tag, sha):
    if not (meta.get('draft') is True and meta.get('prerelease') is True
            and meta.get('tag_name') == tag and meta.get('target_commitish') == sha):
        raise RuntimeError('Existing release is not the matching development draft; preserved unchanged.')


def verify_asset(asset, path, destination):
    if asset['size'] != path.stat().st_size or asset.get('state') != 'uploaded':
        raise RuntimeError('Asset size/state mismatch; preserved without replacement: ' + path.name)
    api(f"repos/{REPO}/releases/assets/{asset['id']}", output=destination)
    if digest(destination) != digest(path):
        raise RuntimeError('Downloaded asset hash mismatch: ' + path.name)


def main():
    if os.environ.get('GITHUB_REPOSITORY') != REPO:
        raise RuntimeError('Only the original private repository is supported.')
    event, ref = os.environ.get('GITHUB_EVENT_NAME'), os.environ.get('GITHUB_REF', '')
    if event != 'workflow_dispatch' and not (event == 'push' and ref.startswith('refs/heads/delivery/')):
        raise RuntimeError('Delivery requires an explicit dispatch or delivery branch push.')
    sha = os.environ.get('GITHUB_SHA', '')
    run_id, attempt = os.environ.get('GITHUB_RUN_ID', ''), os.environ.get('GITHUB_RUN_ATTEMPT', '')
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or not run_id.isdigit() or not attempt.isdigit():
        raise RuntimeError('Missing immutable source/run identity.')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if head != sha:
        raise RuntimeError('Checkout does not match the requested source SHA.')
    if api(f'repos/{REPO}').get('private') is not True:
        raise RuntimeError('Repository must remain private; no upload attempted.')

    apk_dir = ROOT / 'app/build/outputs/apk/debug'
    metadata = json.loads((apk_dir / 'output-metadata.json').read_text())
    if metadata.get('applicationId') != 'cn.jiaoban.dev' or metadata.get('variantName') != 'debug':
        raise RuntimeError('Expected the unchanged application ID and debug variant.')
    elements = metadata.get('elements', [])
    if len(elements) != 1 or elements[0].get('outputFile') != 'app-debug.apk':
        raise RuntimeError('Expected exactly one development APK.')
    apk = apk_dir / 'app-debug.apk'
    if not apk.is_file() or apk.stat().st_size == 0:
        raise RuntimeError('Development APK is missing or empty.')

    tag = f'dev-apk-{run_id}-{attempt}'
    run_url = f'https://github.com/{REPO}/actions/runs/{run_id}'
    out = ROOT / '.cloud/apk-delivery'
    out.mkdir(parents=True, exist_ok=True)
    apk_out = out / f'jiyixia-DEVELOPMENT-{sha[:12]}.apk'
    shutil.copyfile(apk, apk_out)
    sha_out = out / 'SHA256SUMS.txt'
    sha_out.write_text(f'{digest(apk_out)}  {apk_out.name}\n', encoding='utf-8')
    manifest = out / 'build-info.json'
    manifest.write_text(json.dumps({
        'repository': REPO, 'sourceCommit': sha, 'workflowRun': run_url,
        'runAttempt': int(attempt), 'applicationId': 'cn.jiaoban.dev',
        'versionCode': elements[0]['versionCode'], 'versionName': elements[0]['versionName'],
        'variant': 'debug', 'developmentOnly': True, 'originalSigningKey': False,
        'upgradeApproved': False, 'apk': apk_out.name,
        'size': apk_out.stat().st_size, 'sha256': digest(apk_out),
    }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    files = {p.name: p for p in [apk_out, sha_out, manifest]}

    # Tag endpoints can hide drafts. Enumerate authenticated releases, then use the ID.
    matches, page = [], 1
    while True:
        releases = api(f'repos/{REPO}/releases?per_page=100&page={page}')
        matches += [release for release in releases if release['tag_name'] == tag]
        if len(releases) < 100:
            break
        page += 1
    if len(matches) > 1:
        raise RuntimeError('Multiple matching drafts; preserve and inspect rather than guessing.')
    if matches:
        meta = matches[0]
    else:
        meta = api(f'repos/{REPO}/releases', method='POST', data={
            'tag_name': tag, 'target_commitish': sha,
            'name': f'开发 APK · 非升级包 · {sha[:12]}',
            'body': f'仅供开发测试，保持未发布草稿。\n\n源码：`{sha}`\n运行：{run_url}\n\n'
                    '未使用原证书，不可作为现有应用的正式升级包。不要卸载、清除资料或降级。'
                    '需要试装时仅使用可丢弃模拟器。三项附件均下载回读并核对 SHA256 后才报告交付完成。',
            'draft': True, 'prerelease': True, 'make_latest': 'false',
        })
    require_draft(meta, tag, sha)
    endpoint = f"repos/{REPO}/releases/{meta['id']}"
    with tempfile.TemporaryDirectory(prefix='apk-readback-') as tmp:
        verified = {}
        for asset in meta['assets']:
            name = asset['name']
            if name not in files or name in verified:
                raise RuntimeError('Unknown/duplicate asset; draft preserved unchanged.')
            verify_asset(asset, files[name], Path(tmp) / name)
            verified[name] = asset['id']
        for name, path in files.items():
            if name in verified:
                continue
            api(f"https://uploads.github.com/repos/{REPO}/releases/{meta['id']}/assets?name={quote(name)}",
                method='POST', upload=path)
        final = api(endpoint)
        require_draft(final, tag, sha)
        if len(final['assets']) != len(files) or {a['name'] for a in final['assets']} != set(files):
            raise RuntimeError('Incomplete draft; nothing published or deleted.')
        for asset in final['assets']:
            name = asset['name']
            if name in verified and verified[name] != asset['id']:
                raise RuntimeError('Verified asset changed during delivery.')
            verify_asset(asset, files[name], Path(tmp) / name)
    receipt = {'draft': True, 'private': True, 'readBackVerified': True,
               'sourceCommit': sha, 'runUrl': run_url, 'releaseId': final['id'],
               'url': final['html_url'], 'assets': [
                   {'name': name, 'sha256': digest(path), 'size': path.stat().st_size}
                   for name, path in files.items()]}
    (out / 'delivery-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as stream:
            stream.write(f"### 开发 APK 已交付（私有未发布草稿）\n\n[下载三项附件]({final['html_url']})\n\n"
                         f"源码：`{sha}`\n\nAPK SHA256：`{digest(apk_out)}`\n\n"
                         '所有附件已下载回读校验。需以有写权限的 GitHub 账号登录查看草稿。'
                         '不是正式升级包；不要在存有资料的手机卸载、清除资料或安装此开发包。\n')
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
