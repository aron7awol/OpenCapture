import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'controller'))
import server
spec = importlib.util.spec_from_file_location('benchmark_ios',ROOT/'scripts/benchmark_ios.py')
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def request():
    return dict(protocolVersion=1,id='11111111-1111-4111-8111-111111111111',format='bayerRaw',
                quality='speed',frameCount=2,settleMilliseconds=750,context={},includePreview=False,
                exposureSeconds=1.,iso=100.,focusPosition=.5,whiteBalance=dict(red=2.,green=1.,blue=1.5))


class CaptureExperimentTests(unittest.TestCase):
    def test_supported_modes_and_normal_request(self):
        server.validate_request(request())
        for mode in bench.MODES[1:]:
            value=dict(request(),captureExperiment=mode)
            self.assertEqual(server.validate_request(value),value)

    def test_rejects_unknown_automatic_processed_and_cross_platform_modes(self):
        base=dict(request(),captureExperiment=bench.MODES[1])
        for change in (dict(captureExperiment='unknown'),dict(captureExperiment=None),
                       dict(exposureSeconds=None,iso=None),dict(focusPosition=None),dict(whiteBalance=None),
                       dict(includePreview=True),dict(format='jpeg'),dict(operation='applyControls',frameCount=1),
                       dict(captureScheduling='queued-preview'),
                       dict(averaging=dict(mode='bayer-mean-v2',emptyFramePolicy='relative',keepOriginals=True))):
            with self.subTest(change=change),self.assertRaises(server.Invalid):
                server.validate_request(dict(base,**change))

    def test_streaming_is_supported_without_changing_admission_contract(self):
        server.validate_request(dict(request(),captureExperiment=bench.MODES[2],streaming=dict(mode='originals-v1')))

    def test_lan_only_and_safe_receipts(self):
        self.assertEqual(bench.address('192.168.1.2:8767'),('192.168.1.2',8767))
        for value in ('https://192.168.1.2','http://8.8.8.8:8767','http://u:p@192.168.1.2:8767','http://192.168.1.2/path'):
            with self.assertRaises(ValueError):bench.address(value)
        with tempfile.TemporaryDirectory() as root:
            receipt=dict(name='../secret',bytes=1,sha256='a'*64)
            with self.assertRaises(ValueError):bench.receipt_path(Path(root),receipt)
            for size in (0,134217729,True):
                with self.assertRaises(ValueError):bench.receipt_path(Path(root),dict(receipt,name='frame-0001.dng',bytes=size))

    def test_assessment_catches_stale_and_mismatched_originals(self):
        r=request()
        frames=[dict(index=i,isBayerRAW=True,isAppleProRAW=False,actualExposureSeconds=1.,actualISO=100.,
                     deviceExposureSeconds=1.,deviceISO=100.,lensPosition=.5,whiteBalance=r['whiteBalance'],
                     timestampSeconds=10.+i,metadataFile=dict(name=f'frame-{i:04}.json')) for i in (1,2)]
        with tempfile.TemporaryDirectory() as root:
            folder=Path(root)
            for f in frames:
                bench.save(folder/f['metadataFile']['name'],dict(captureDiagnostics=dict(sampleIntegrity=dict(assessment='consistent_native_buffer_and_file'))))
            manifest=dict(request=r,frames=frames)
            self.assertTrue(bench.assess(r,manifest,folder)['metadataChecksPassed'])
            bad=copy.deepcopy(manifest);bad['frames'][1]['timestampSeconds']=11.
            self.assertIn('2:exposure_timestamp_gap',bench.assess(r,bad,folder)['failures'])
            bad=copy.deepcopy(manifest);bad['frames'][0]['actualISO']=200.
            self.assertIn('1:actualISO',bench.assess(r,bad,folder)['failures'])
            bad=copy.deepcopy(manifest);bad['request']['exposureSeconds']=2.
            with self.assertRaises(ValueError):bench.assess(r,bad,folder)


if __name__ == '__main__': unittest.main()
