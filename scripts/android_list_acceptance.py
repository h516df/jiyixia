"""复用现有验收工具，复验键盘展开时列表底部的真实滚动路径。"""
import argparse
import hashlib
import json
from pathlib import Path
import android_acceptance as test

args = argparse.ArgumentParser()
args.add_argument('--serial', required=True)
opts = args.parse_args()
test.SERIAL = opts.serial
if test.adb('shell', 'getprop', 'ro.boot.qemu.avd_name').strip() != 'Jiaoban_Codex_API36':
    raise SystemExit('Only use the disposable Jiaoban test emulator.')
test.seed_records()
xml = test.launch()
test.check('今天分组可见', '今天要交的' in test.visible(xml))
xml = test.scroll_until(test.dump_ui('list-ready'), '已过期')
test.check('滚动后过期入口可见', any('已过期 1 件' == t for t in test.visible(xml)))
test.check('点开真实过期入口', test.tap_text(xml, '已过期') is not None)
import time
time.sleep(1)
xml = test.scroll_until(test.dump_ui('overdue-expanded'), '早就过期的报销单')
test.check('展开后过期记录可见', '早就过期的报销单' in test.visible(xml))
test.check('过期区截图', test.shot('02-过期区.png'))
report = {'serial': test.SERIAL, 'apkSha256': hashlib.sha256(test.APK.read_bytes()).hexdigest(),
          'results': [{'name': n, 'passed': ok, 'detail': detail} for n, ok, detail in test.RESULTS]}
(test.OUT / 'list-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
raise SystemExit(0 if all(r[1] for r in test.RESULTS) else 1)
