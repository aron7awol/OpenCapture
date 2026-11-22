import copy
import importlib.util
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

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
    def test_runner_archives_each_mode_before_ack_and_never_changes_display(self):
        events=[]
        class FakePeer:
            def __init__(self,target):self.job=None;self.files={};self.session='11111111-1111-4111-8111-111111111111'
            def json(self,method,path,body=None):
                events.append((method,path))
                caps=dict(platform='ios',cameraID='main',deviceID='fixture',captureExperiments=list(bench.MODES[1:]))
                if path in ('/oc/v1/info','/oc/v1/camera'):return dict(capabilities=caps)
                if path=='/oc/v1/connect':return {}
                if path=='/oc/v1/jobs':
                    self.job=body;frames=[]
                    def file(name,data):
                        self.files[name]=data
                        return dict(name=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
                    for i in range(1,body['frameCount']+1):
                        sidecar=dict(captureDiagnostics=dict(sampleIntegrity=dict(assessment='consistent_native_buffer_and_file')))
                        if 'captureExperiment' in body:sidecar['captureExperiment']=dict(mode=body['captureExperiment'])
                        frames.append(dict(index=i,isBayerRAW=True,actualExposureSeconds=1.,actualISO=100.,
                            deviceExposureSeconds=1.,deviceISO=100.,lensPosition=.5,whiteBalance=body['whiteBalance'],timestampSeconds=10.+i,
                            file=file(f'frame-{i:04}.dng',b'synthetic RAW fixture'),
                            metadataFile=file(f'frame-{i:04}.json',json.dumps(sidecar).encode())))
                    self.manifest=json.dumps(dict(request=body,camera=caps,frames=frames)).encode()
                    return {}
                if path.endswith('/ack'):
                    self_hash=hashlib.sha256(self.manifest).hexdigest()
                    assert body['resultSha256']==self_hash
                    assert list(Path(output).glob('*/manifest.json'))
                    return {}
                if path=='/oc/v1/disconnect':return {}
                return dict(status='complete',cameraReady=True,resultSha256=hashlib.sha256(self.manifest).hexdigest())
            def request(self,method,path,body=None,*,receipt=None,destination=None):
                if path.endswith('/result'):return self.manifest
                assert receipt and destination
                data=self.files[receipt['name']]
                assert hashlib.sha256(data).hexdigest()==receipt['sha256']
                destination.write_bytes(data)
        with tempfile.TemporaryDirectory() as root:
            source=Path(root)/'manual.json';bench.save(source,request())
            output=str(Path(root)/'benchmark')
            args=SimpleNamespace(output=output,request=str(source),phone='127.0.0.1:8767',camera='main',
                                 frames=2,repeats=1,modes=list(bench.MODES))
            with patch.object(bench,'Peer',FakePeer):bench.run(args)
            results=json.loads((Path(output)/'summary.json').read_text())
            self.assertEqual([r['mode'] for r in results],list(bench.MODES))
            self.assertTrue(all(r['metadataChecksPassed'] and not r['physicalMeasurementQualified'] for r in results))
            self.assertEqual(len(list(Path(output).glob('*/*.dng'))),8)
            self.assertEqual(sum(path.endswith('/ack') for _,path in events),4)
            self.assertTrue(all(path.startswith('/oc/v1/') for _,path in events))

    def test_supported_modes_and_normal_request(self):
        server.validate_request(request())
        for mode in bench.MODES[1:]:
            value=dict(request(),captureExperiment=mode)
            self.assertEqual(server.validate_request(value),value)

    def test_rejects_unknown_automatic_processed_and_cross_platform_modes(self):
        base=dict(request(),captureExperiment=bench.MODES[1])
        for change in (dict(captureExperiment='ios-bayer-only'),dict(captureExperiment='unknown'),dict(captureExperiment=None),
                       dict(exposureSeconds=None,iso=None),dict(focusPosition=None),dict(whiteBalance=None),
                       dict(includePreview=True),dict(format='jpeg'),dict(operation='applyControls',frameCount=1),
                       dict(captureScheduling='queued-preview'),
                       dict(averaging=dict(mode='bayer-mean-v2',emptyFramePolicy='relative',keepOriginals=True))):
            with self.subTest(change=change),self.assertRaises(server.Invalid):
                server.validate_request(dict(base,**change))

    def test_streaming_is_supported_without_changing_admission_contract(self):
        server.validate_request(dict(request(),captureExperiment=bench.MODES[2],streaming=dict(mode='originals-v1')))

    def test_controller_requires_advertised_mode(self):
        with tempfile.TemporaryDirectory() as root:
            state=server.State(root,'test-token')
            value=dict(request(),captureExperiment=bench.MODES[1])
            with self.assertRaises(server.Invalid):state.submit(value)
            state.capabilities=dict(captureExperiments=[bench.MODES[1]])
            self.assertEqual(state.submit(value)['status'],'queued')

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
