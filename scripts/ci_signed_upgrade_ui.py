#!/usr/bin/env python3
"""Use only a disposable QA emulator; verify a real same-certificate in-place install.

The v0.23 release is not debuggable. Make one visible record through its own UI,
then upgrade without uninstalling or clearing storage and look for that exact record.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path('app/build/outputs/signed-upgrade')
OLD = ROOT / 'old/Jiaoban-v0.23-universal.apk'
NEW = ROOT / 'new/Jiyixia-v0.24-universal.apk'
MARKER = 'V023V024UPGRADE20261101'


def adb(*args, timeout=70, check=True):
    p = subprocess.run(['adb', *map(str, args)], capture_output=True, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError(f'adb {args}: {p.stderr.decode("utf-8", "replace")[:350]}')
    return p.stdout.decode('utf-8', 'replace')


def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/v024-window.xml')
    xml = adb('exec-out', 'cat', '/sdcard/v024-window.xml')
    return ET.fromstring(xml)


def find_element(label, attempts=14):
    for _ in range(attempts):
        for n in ui().iter('node'):
            if label in (n.get('text', ''), n.get('content-desc', '')):
                return n
        time.sleep(.35)
    raise AssertionError(f'UI element not found: {label}')


def click(label):
    node = find_element(label)
    coords = re.findall(r'\[(\d+),(\d+)\]', node.get('bounds', ''))
    if len(coords) != 2:
        raise AssertionError(f'No bounds: {label}')
    (x0, y0), (x1, y1) = ((int(x), int(y)) for x, y in coords)
    adb('shell', 'input', 'tap', str((x0+x1)//2), str((y0+y1)//2))
    time.sleep(.5)


def screenshot(name):
    p = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, timeout=30, check=True)
    (ROOT / name).write_bytes(p.stdout)


def wait_text(value):
    for _ in range(22):
        if any(value in n.get('text', '') for n in ui().iter('node')):
            return
        time.sleep(.35)
    raise AssertionError('Saved record not visible: ' + value)


def main():
    avd = adb('shell', 'getprop', 'ro.boot.qemu.avd_name').strip()
    assert avd == 'Shiye_Upgrade_API35', 'Must run only on dedicated disposable emulator'
    adb('install', '-r', OLD)
    adb('shell', 'am', 'start', '-W', '-n', 'cn.jiaoban/.MainActivity')
    find_element('随手记录正文')
    click('随手记录正文')
    adb('shell', 'input', 'text', MARKER)
    click('记下')
    wait_text(MARKER)
    screenshot('01-original-v023.png')
    print('PASS v0.23 native UI persisted a synthetic record', flush=True)
    adb('shell', 'am', 'force-stop', 'cn.jiaoban')
    output = adb('install', '-r', NEW)
    assert 'Success' in output, 'In-place APK install failed'
    assert 'versionCode=24' in adb('shell', 'dumpsys', 'package', 'cn.jiaoban')
    adb('shell', 'am', 'start', '-W', '-n', 'cn.jiaoban/.MainActivity')
    wait_text(MARKER)
    screenshot('02-upgraded-v024.png')
    print('PASS signed v0.23→v0.24 in-place upgrade preserved real app data', flush=True)
    (ROOT / 'upgrade-proof.txt').write_text(
        f'AVD: {avd}\nOld APK: {OLD}\nNew APK: {NEW}\n'
        f'Same certificate, package cn.jiaoban, marker {MARKER} survived in-place upgrade.\n',
        encoding='utf-8')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'FAIL signed upgrade: {error}', file=sys.stderr, flush=True)
        try:
            screenshot('failure.png')
        except Exception:
            pass
        raise
