import copy
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server


class RequestPrecisionTests(unittest.TestCase):
    def test_swift_float32_echo_keeps_request_identity(self):
        sent = dict(iso=803.9034473400001, exposureSeconds=.06657,
                    focusPosition=.78039217,
                    whiteBalance=dict(red=2.2172852, green=1., blue=1.5371094),
                    id='same-id', frameCount=6, context={'role': 'reference'})
        echoed = copy.deepcopy(sent)
        echoed['iso'] = 803.90344
        original = copy.deepcopy(sent)
        self.assertEqual(server.canonical_request(sent), server.canonical_request(echoed))
        self.assertEqual(sent, original)
        for key, value in [('iso', 804.), ('exposureSeconds', .066570001),
                           ('focusPosition', .7804), ('frameCount', 5),
                           ('id', 'different-id'), ('context', {'role': 'different'})]:
            changed = copy.deepcopy(echoed)
            changed[key] = value
            self.assertNotEqual(server.canonical_request(sent), server.canonical_request(changed), key)

    def test_omitted_null_controls_remain_equivalent(self):
        self.assertEqual(server.canonical_request({}), server.canonical_request(dict(iso=None, whiteBalance=None)))


if __name__ == '__main__':
    unittest.main()
