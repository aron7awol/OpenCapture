import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('publish_ios_feed', ROOT / 'scripts/publish_ios_feed.py')
feed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feed)
IPA = b'synthetic-ipa'


def document(build=45, modern=False):
    tag = f'v0.4.6-ios.{build}' if modern else f'v0.4.6-b{build}'
    base = feed.BASE + f'download/{tag}/'
    return {'apps': [{'bundleIdentifier': 'io.github.aron7awol.OpenCapture', 'iconURL': base + 'icon.png',
        'versions': [{'version': '0.4.6', 'buildVersion': str(build), 'downloadURL': base + 'OpenCapture.ipa',
                      'size': len(IPA), 'sha256': hashlib.sha256(IPA).hexdigest()}]}]}


def encoded(value):
    return json.dumps(value).encode()


class IOSFeedTests(unittest.TestCase):
    def test_old_and_new_tags_keep_identical_app_identity(self):
        self.assertEqual(feed.describe(encoded(document()))[0], (0, 4, 6, 45))
        self.assertEqual(feed.describe(encoded(document(46, True)))[1], 'v0.4.6-ios.46')
        with (ROOT / 'Resources/Info.plist').open('rb') as stream:
            self.assertEqual(plistlib.load(stream)['OpenCaptureSourceURL'], feed.FEED)

    def test_invalid_identity_asset_and_integrity_fields(self):
        for key, value in [('downloadURL', 'https://evil.invalid/app.ipa'), ('size', True),
                           ('sha256', 'broken'), ('buildVersion', '45/other')]:
            d = document(); d['apps'][0]['versions'][0][key] = value
            with self.assertRaises(ValueError): feed.describe(encoded(d))
        d = document(); d['apps'][0]['bundleIdentifier'] = 'another.app'
        with self.assertRaises(ValueError): feed.describe(encoded(d))

    def test_downgrade_and_same_version_replacement_refused(self):
        old = encoded(document())
        feed.check_previous(old, old)
        feed.check_previous(old, encoded(document(46, True)))
        with self.assertRaises(ValueError): feed.check_previous(old, encoded(document(44)))
        altered = document(); altered['name'] = 'Changed'
        with self.assertRaises(ValueError): feed.check_previous(old, encoded(altered))

    def exercise(self, *, exists=True, promote=False, corrupt=False, concurrent=False, write=True):
        old = encoded(document())
        new = encoded(document(46, True)) if promote else old
        current = {feed.FEED: old, feed.LEGACY: old}
        tag = feed.describe(new)[1]
        reads = 0
        def read(url, limit):
            nonlocal reads
            if url.endswith('/OpenCapture.ipa'): return b'bad' if corrupt else IPA
            if url.endswith('/icon.png'): return b'\x89PNG\r\n\x1a\nicon'
            if url == feed.FEED:
                reads += 1
                if concurrent and reads > 1: return encoded(document(47, True))
            if url == feed.BASE + f'download/{tag}/altstore.json': return new
            return current[url]
        def gh(*args):
            if args[0] == 'api' and '/commits/' in args[1]: return 'a' * 40
            if args[0] == 'api': return json.dumps([[{'tag_name': 'ios-updates'}]] if exists else [[]])
            if args[:2] in [('release', 'create'), ('release', 'upload')]: current[feed.FEED] = new
            if args[:2] == ('release', 'edit'): current[feed.LEGACY] = new
            return ''
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'altstore.json'; path.write_bytes(new)
            with patch.object(feed, 'read_public', side_effect=read), patch.object(feed, 'gh', side_effect=gh) as cli:
                if corrupt or concurrent:
                    with self.assertRaises(ValueError): feed.publish(path, write=write)
                    self.assertFalse(any(c.args[0] == 'release' for c in cli.call_args_list))
                else:
                    feed.publish(path, write=write, promote=tag if promote else None)
                return cli.call_args_list, current, old, new

    def test_bootstrap_preserves_legacy_bytes_and_latest(self):
        calls, current, old, _ = self.exercise(exists=False)
        create = next(c.args for c in calls if c.args[:2] == ('release', 'create'))
        self.assertIn('--latest=false', create)
        self.assertEqual(current[feed.LEGACY], old)
        self.assertFalse(any(c.args[:2] == ('release', 'edit') for c in calls))

    def test_future_release_updates_stable_then_legacy(self):
        calls, current, _, new = self.exercise(promote=True)
        operations = [c.args[:2] for c in calls if c.args[0] == 'release']
        self.assertEqual(operations, [('release', 'upload'), ('release', 'edit')])
        self.assertEqual(current[feed.FEED], new)
        self.assertEqual(current[feed.LEGACY], new)

    def test_corrupt_public_ipa_never_changes_feeds(self):
        self.exercise(corrupt=True)

    def test_concurrent_feed_change_aborts(self):
        self.exercise(concurrent=True)

    def test_dry_run_does_not_write(self):
        calls, _, _, _ = self.exercise(write=False)
        self.assertFalse(any(c.args[0] == 'release' for c in calls))

    def test_wrong_latest_tag_is_rejected_before_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'altstore.json'; path.write_bytes(encoded(document()))
            with patch.object(feed, 'read_public') as read:
                with self.assertRaises(ValueError): feed.publish(path, write=True, promote='android-v0.4.10-b18')
                read.assert_not_called()
