import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('delivery', Path(__file__).with_name('deliver_apk.py'))
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


class DeliverySafetyTest(unittest.TestCase):
    def test_refuses_public_repository_before_preparing_or_uploading(self):
        env = {'GITHUB_REPOSITORY': delivery.REPO, 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '1'}
        with patch.dict(delivery.os.environ, env, clear=True), \
                patch.object(delivery.subprocess, 'check_output', return_value='a' * 40), \
                patch.object(delivery, 'api', return_value={'private': False}) as api:
            with self.assertRaisesRegex(RuntimeError, 'remain private'):
                delivery.main()
            self.assertEqual(api.call_count, 1)

    def test_pull_requests_cannot_deliver(self):
        with patch.dict(delivery.os.environ, {'GITHUB_REPOSITORY': delivery.REPO,
                                            'GITHUB_EVENT_NAME': 'pull_request'}, clear=True), \
                patch.object(delivery, 'api') as api:
            with self.assertRaisesRegex(RuntimeError, 'explicit dispatch'):
                delivery.main()
            api.assert_not_called()

    def test_published_or_wrong_source_release_is_never_reused(self):
        valid = {'draft': True, 'prerelease': True, 'tag_name': 'dev-apk-1-1', 'target_commitish': 'a' * 40}
        delivery.require_draft(valid, 'dev-apk-1-1', 'a' * 40)
        for update in [{'draft': False}, {'prerelease': False}, {'target_commitish': 'b' * 40}]:
            with self.subTest(update=update), self.assertRaises(RuntimeError):
                delivery.require_draft(dict(valid, **update), 'dev-apk-1-1', 'a' * 40)

    def test_corrupt_download_fails_hash_verification(self):
        with tempfile.TemporaryDirectory() as tmp:
            original, downloaded = Path(tmp) / 'original.apk', Path(tmp) / 'readback.apk'
            original.write_bytes(b'original')
            def corrupt(*args, **kwargs):
                kwargs['output'].write_bytes(b'corrupt!')
            with patch.object(delivery, 'api', side_effect=corrupt), \
                    self.assertRaisesRegex(RuntimeError, 'hash mismatch'):
                delivery.verify_asset({'id': 1, 'size': 8, 'state': 'uploaded'}, original, downloaded)


if __name__ == '__main__':
    unittest.main()
