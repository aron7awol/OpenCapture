import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server


class AtomicJsonTests(unittest.TestCase):
    def test_transient_windows_reader_keeps_old_document_until_replace(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'report.json'
            server.atomic_json(path, {'previous': True})
            replace = os.replace
            calls = []
            def blocked_once(source, destination):
                calls.append(source)
                if len(calls) == 1:
                    self.assertEqual(json.loads(path.read_text()), {'previous': True})
                    error = PermissionError('sharing violation')
                    error.winerror = 32
                    raise error
                replace(source, destination)
            with patch.object(server.os, 'replace', side_effect=blocked_once), patch.object(server.time, 'sleep'):
                server.atomic_json(path, {'next': True})
            self.assertEqual(json.loads(path.read_text()), {'next': True})
            self.assertEqual(len(calls), 2)
            self.assertEqual(list(Path(root).glob('*.tmp')), [])

    def test_persistent_denial_is_bounded_and_preserves_old_document(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'report.json'
            server.atomic_json(path, {'previous': True})
            error = PermissionError('denied')
            error.winerror = 5
            with patch.object(server.os, 'replace', side_effect=error) as replace, patch.object(server.time, 'sleep'):
                with self.assertRaises(PermissionError):
                    server.atomic_json(path, {'next': True})
            self.assertEqual(replace.call_count, 9)
            self.assertEqual(json.loads(path.read_text()), {'previous': True})
            self.assertEqual(list(Path(root).glob('*.tmp')), [])

    def test_invalid_json_never_replaces_previous(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'report.json'
            server.atomic_json(path, {'previous': True})
            with self.assertRaises(ValueError):
                server.atomic_json(path, {'invalid': float('nan')})
            self.assertEqual(json.loads(path.read_text()), {'previous': True})
            self.assertEqual(list(Path(root).glob('*.tmp')), [])

    @unittest.skipUnless(os.name == 'nt', 'Windows sharing semantics')
    def test_real_windows_open_reader_can_release_during_retry(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'report.json'
            server.atomic_json(path, {'previous': True})
            reader = path.open('rb')
            release = threading.Timer(.12, reader.close)
            release.start()
            try:
                server.atomic_json(path, {'next': True})
            finally:
                release.join()
                reader.close()
            self.assertEqual(json.loads(path.read_text()), {'next': True})


if __name__ == '__main__':
    unittest.main()
