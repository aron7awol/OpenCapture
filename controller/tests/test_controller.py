import copy
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import plistlib
import sys
import tempfile
import threading
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "controller"))
import server


def request():
    return {"protocolVersion": 1, "id": str(uuid.uuid4()), "format": "bayerRaw", "quality": "speed",
            "frameCount": 1, "settleMilliseconds": 500, "context": {"label": "Synthetic test"}}


def average_manifest():
    job = dict(request(), frameCount=2, averaging=dict(mode='bayer-mean-v1',emptyFramePolicy='illuminated',keepOriginals=False))
    files, frames = {}, []
    def receipt(name, data, media):
        files[name] = data
        return dict(name=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),mediaType=media)
    for i in range(1,4):
        frames.append(dict(index=i,width=8,height=8,timestampSeconds=i,deviceExposureSeconds=.01,deviceISO=100,
            actualExposureSeconds=.01,actualISO=100,lensPosition=.5,whiteBalance=dict(red=1,green=1,blue=1),isBayerRAW=True,
            file=receipt(f'frame-{i:04d}.dng',b'original'+bytes([i]),'image/dng'),
            metadataFile=receipt(f'frame-{i:04d}.json',b'{}','application/json')))
    aggregation=dict(version=1,mode='bayer-mean-v1',status='complete',targetFrames=2,attemptedFrames=3,
        acceptedIndices=[1,3],rejected=[dict(index=2,reason='empty_signal')],referenceIndex=1,retainedOriginalIndices=[1,2],
        domain='black_subtracted_bayer_site_relative_signal',width=4,height=4,rawWidth=8,rawHeight=8,
        activeArea=[0,0,8,8],blackLevels=[256]*4,whiteLevel=4095,cfa=[0,1,1,2],
        files=dict(moments=receipt('average.f32',bytes(4*4*24),'application/octet-stream'),
                   clipping=receipt('average-clips.u8',bytes(4*4),'application/octet-stream')))
    return dict(protocolVersion=1,request=job,camera=dict(platform='android',frameAveragingModes=['bayer-mean-v1']),
        frames=frames,aggregation=aggregation,startedAt='synthetic',completedAt='synthetic',processing='synthetic'),files


class ContractTests(unittest.TestCase):
    def test_ios_average_v2_crop_and_radial_descriptor(self):
        m,_ = average_manifest()
        m['request']['averaging']['mode'] = 'bayer-mean-v2'
        m['camera'].update(platform='ios',frameAveragingModes=['bayer-mean-v2'])
        a = m['aggregation']
        a.update(version=2,mode='bayer-mean-v2',domain='black_subtracted_dng_corrected_bayer_site_relative_signal',rawWidth=10,
                 radialGain=[dict(id=3,version='1.3.0.0',flags=0,coefficients=[.25,0,0,0,0],center=[.5,.5])])
        self.assertIn('average.f32',server.manifest_files(m))
        for change in [dict(version=1),dict(domain='black_subtracted_bayer_site_relative_signal'),dict(radialGain=None),
                       dict(radialGain=[dict(id=9)]),dict(radialGain=[dict(a['radialGain'][0],center=[float('nan'),.5])])]:
            changed = dict(m,aggregation=dict(a,**change))
            with self.subTest(change=change),self.assertRaises(server.Invalid):server.manifest_files(changed)

    def test_average_contract_retains_diagnostics_and_only_selected_originals(self):
        m, _ = average_manifest()
        files = server.manifest_files(m)
        self.assertNotIn('frame-0003.dng',files)
        self.assertIn('frame-0003.json',files)
        self.assertIn('frame-0002.dng',files)
        self.assertIn('average.f32',files)

    def test_average_rejects_underfill_bad_indices_crop_and_sizes(self):
        for field,value in [('acceptedIndices',[1]),('retainedOriginalIndices',[1]),('acceptedIndices',[1,1]),
                            ('targetFrames',1),('activeArea',[0,0,10,8]),('width',False),('whiteLevel',0),
                            ('status','insufficient'),('attemptedFrames',65),('cfa',[0,1,1,True])]:
            m,_=average_manifest();m['aggregation'][field]=value
            with self.subTest(field=field),self.assertRaises(server.Invalid):server.manifest_files(m)
        m,_=average_manifest();m['aggregation']['files']['moments']['bytes']-=1
        with self.assertRaises(server.Invalid):server.manifest_files(m)

    def test_average_is_explicit_bayer_capture_only(self):
        m,_=average_manifest()
        for changes in (dict(format='jpeg'),dict(operation='applyControls',frameCount=1),dict(averaging=None)):
            with self.assertRaises(server.Invalid):server.validate_request(dict(m['request'],**changes))
        m['request']['averaging']['keepOriginals']=1
        with self.assertRaises(server.Invalid):server.validate_request(m['request'])
    def test_proraw_requires_explicit_quality_request(self):
        job = dict(request(), format='appleProRaw')
        with self.assertRaises(server.Invalid):
            server.validate_request(job)
        job['quality'] = 'quality'
        server.validate_request(job)

    def test_valid_auto_and_manual(self):
        job = request()
        server.validate_request(job)
        job.update(exposureSeconds=0.0333, iso=100, focusPosition=0.5,
                   whiteBalance={"red": 1.1, "green": 1.0, "blue": 1.4})
        server.validate_request(job)

    def test_proraw_is_explicit_and_quality_prioritized(self):
        job = request()
        job.update(format="appleProRaw", quality="quality")
        server.validate_request(job)
        job["quality"] = "speed"
        with self.assertRaises(server.Invalid):
            server.validate_request(job)

    def test_rejects_invalid_fields(self):
        for key, value in [("frameCount", 0), ("frameCount", 33), ("frameCount", True),
                           ("protocolVersion", True), ("protocolVersion", 2), ("id", "../outside"),
                           ("exposureSeconds", 0.02), ("iso", 100), ("focusPosition", float("nan")),
                           ("focusPosition", -1), ("quality", "quality"), ("format", "proRaw"),
                           ("settleMilliseconds", -1), ("context", {"a": 12}), ("unknown", True)]:
            with self.subTest(key=key, value=value), self.assertRaises(server.Invalid):
                job = request()
                job[key] = value
                server.validate_request(job)

    def test_controls_contract(self):
        job = request()
        job["operation"] = "applyControls"
        server.validate_request(job)
        job["frameCount"] = 2
        with self.assertRaises(server.Invalid):
            server.validate_request(job)
        job.update(frameCount=1, operation="execute")
        with self.assertRaises(server.Invalid):
            server.validate_request(job)

    def test_nonfinite_and_duplicate_json(self):
        for body in [b'{"a": NaN}', b'{"a":Infinity}', b'{"a":1,"a":2}', b'not json', b'\xff']:
            with self.subTest(body=body), self.assertRaises(server.Invalid):
                server.parse_json(body)

    def test_fixture(self):
        job = server.parse_json((ROOT / "protocol/request.example.json").read_bytes())
        server.validate_request(job)


class HTTPTests(unittest.TestCase):
    def test_average_transfer_completion_and_idempotency(self):
        m,files=average_manifest();job=m['request']
        self.assertEqual(self.call('POST','/oc/v1/jobs',job)[0],409)
        self.state.capabilities=m['camera']
        self.assertEqual(self.call('POST','/oc/v1/jobs',job)[0],200)
        lease=self.call('GET','/oc/v1/lease')[1]['leaseToken']
        expected=server.manifest_files(m)
        for name in expected:
            status,receipt=self.upload(job,lease,name,files[name])
            self.assertEqual(status,200);self.assertEqual(receipt,expected[name])
        endpoint=f'/oc/v1/jobs/{job["id"]}/complete'
        for _ in range(2):self.assertEqual(self.call('POST',endpoint,m,{'X-OpenCapture-Lease':lease})[0],200)
        self.assertFalse((self.state.jobs[job['id']]['directory']/'frame-0003.dng').exists())

    def test_average_payload_requires_opt_in(self):
        job,lease=self.leased()
        self.assertEqual(self.upload(job,lease,'average.f32',b'data')[0],400)
    def test_proraw_manifest_requires_distinct_declaration(self):
        job = dict(request(), format='appleProRaw', quality='quality')
        self.assertEqual(self.call('POST', '/oc/v1/jobs', job)[0], 200)
        lease = self.call('GET', '/oc/v1/lease')[1]['leaseToken']
        value = self.manifest(job, lease)
        endpoint = f'/oc/v1/jobs/{job["id"]}/complete'
        headers = {'X-OpenCapture-Lease': lease}
        self.assertEqual(self.call('POST', endpoint, value, headers)[0], 400)
        value['frames'][0].update(isBayerRAW=False, isAppleProRAW=True)
        self.assertEqual(self.call('POST', endpoint, value, headers)[0], 200)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.state = server.State(self.temp.name, "test-token-0123456789abcdefghijk")
        self.http = server.Controller(("127.0.0.1", 0), self.state)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.stop)

    def stop(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join(2)

    def call(self, method, path, value=None, headers=None, auth=True):
        outgoing = {"Authorization": "Bearer " + self.state.token} if auth else {}
        if isinstance(value, (dict, list)):
            value = json.dumps(value).encode()
            outgoing["Content-Type"] = "application/json"
        if value is None and method in ("POST", "PUT"):
            value = b""
        outgoing.update(headers or {})
        connection = http.client.HTTPConnection(*self.http.server_address, timeout=5)
        connection.request(method, path, value, outgoing)
        response = connection.getresponse()
        body = response.read()
        status = response.status
        connection.close()
        return status, json.loads(body) if response.getheader("Content-Type") == "application/json" else body

    def leased(self):
        job = request()
        self.assertEqual(self.call("POST", "/oc/v1/jobs", job)[0], 200)
        status, lease = self.call("GET", "/oc/v1/lease")
        self.assertEqual(status, 200)
        self.assertEqual(lease["request"], job)
        return job, lease["leaseToken"]

    def upload(self, job, lease, name, data):
        return self.call("PUT", f'/oc/v1/jobs/{job["id"]}/files/{name}', data,
                         {"X-OpenCapture-Lease": lease, "X-Content-SHA256": hashlib.sha256(data).hexdigest()})

    def manifest(self, job, lease):
        records = []
        for name, payload in [("frame-0001.dng", b"II*\x00synthetic-not-a-real-photo"), ("frame-0001.json", b'{"synthetic":true}')]:
            status, receipt = self.upload(job, lease, name, payload)
            self.assertEqual(status, 200)
            records.append(receipt)
        return {"protocolVersion": 1, "request": job, "camera": {"name": "Synthetic"},
                "frames": [{"index": 1, "file": records[0], "metadataFile": records[1], "width": 16, "height": 16,
                            "timestampSeconds": 123.5, "actualExposureSeconds": 0.01, "actualISO": 100,
                            "deviceExposureSeconds": 0.01, "deviceISO": 100, "lensPosition": 0.5,
                            "whiteBalance": {"red": 1, "green": 1, "blue": 1}, "isBayerRAW": True}],
                "startedAt": "2026-11-07T12:00:00Z", "completedAt": "2026-11-07T12:00:01Z", "processing": "synthetic"}

    def test_ui_and_auth(self):
        status, html = self.call("GET", "/", auth=False)
        self.assertEqual(status, 200)
        self.assertIn(b"OpenCapture", html)
        self.assertNotIn(self.state.token.encode(), html)
        self.assertEqual(self.call("GET", "/oc/v1/state", auth=False)[0], 401)
        self.assertEqual(self.call("GET", "/oc/v1/state?token=" + self.state.token, auth=False)[0], 401)

    def test_cross_origin_rejected(self):
        self.assertEqual(self.call("POST", "/oc/v1/jobs", request(), {"Origin": "https://evil.invalid"})[0], 403)

    def test_one_active_job(self):
        job, _ = self.leased()
        self.assertEqual(self.call("POST", "/oc/v1/jobs", request())[0], 409)
        self.assertEqual(self.call("POST", "/oc/v1/jobs", job)[0], 200)
        job["frameCount"] = 2
        self.assertEqual(self.call("POST", "/oc/v1/jobs", job)[0], 409)

    def test_complete_and_idempotency(self):
        job, lease = self.leased()
        manifest = self.manifest(job, lease)
        path = f'/oc/v1/jobs/{job["id"]}/complete'
        headers = {"X-OpenCapture-Lease": lease}
        self.assertEqual(self.call("POST", path, manifest, headers)[0], 200)
        self.assertEqual(self.call("POST", path, manifest, headers)[0], 200)
        self.assertTrue((Path(self.temp.name) / job["id"] / "manifest.json").exists())
        self.assertEqual(self.upload(job, lease, "frame-0001.json", b'{"synthetic":true}')[0], 200)
        self.assertEqual(self.upload(job, lease, "frame-0001.json", b"changed")[0], 409)
        manifest["processing"] = "different"
        self.assertEqual(self.call("POST", path, manifest, headers)[0], 409)

    def test_proraw_dng_upload_requires_distinct_frame_identity(self):
        job = request()
        job.update(format="appleProRaw", quality="quality")
        self.assertEqual(self.call("POST", "/oc/v1/jobs", job)[0], 200)
        lease = self.call("GET", "/oc/v1/lease")[1]["leaseToken"]
        manifest = self.manifest(job, lease)
        path = f'/oc/v1/jobs/{job["id"]}/complete'
        headers = {"X-OpenCapture-Lease": lease}
        self.assertEqual(self.call("POST", path, manifest, headers)[0], 400)
        manifest["frames"][0]["isBayerRAW"] = False
        self.assertEqual(self.call("POST", path, manifest, headers)[0], 400)
        manifest["frames"][0]["isAppleProRAW"] = True
        self.assertEqual(self.call("POST", path, manifest, headers)[0], 200)
        self.assertEqual((Path(self.temp.name) / job["id"] / "frame-0001.dng").read_bytes(), b"II*\x00synthetic-not-a-real-photo")
        self.assertEqual(self.upload(job, lease, "frame-0001.jpg", b"not a DNG")[0], 400)

    def test_rejects_missing_and_mismatched_files(self):
        job, lease = self.leased()
        manifest = self.manifest(job, lease)
        manifest["frames"][0]["file"]["sha256"] = "0" * 64
        self.assertEqual(self.call("POST", f'/oc/v1/jobs/{job["id"]}/complete', manifest, {"X-OpenCapture-Lease": lease})[0], 409)
        self.assertEqual(self.state.jobs[job["id"]]["status"], "leased")

    def test_no_path_escape(self):
        job, lease = self.leased()
        for name in ["../escape", "%2e%2e%2fescape", "frame-9999.dng", "frame-0001.exe", "frame-0001.jpg"]:
            with self.subTest(name=name):
                self.assertIn(self.upload(job, lease, name, b"test")[0], (400, 404))

    def test_bad_hash_rejected_and_partial_removed(self):
        job, lease = self.leased()
        status, _ = self.call("PUT", f'/oc/v1/jobs/{job["id"]}/files/frame-0001.dng', b"test",
                              {"X-OpenCapture-Lease": lease, "X-Content-SHA256": "0" * 64})
        self.assertEqual(status, 422)
        self.assertEqual(list(Path(self.temp.name).rglob("*.part")), [])
        self.assertFalse(self.state.jobs[job["id"]]["files"])

    def test_wrong_lease(self):
        job, _ = self.leased()
        self.assertEqual(self.upload(job, "x" * 32, "frame-0001.dng", b"test")[0], 403)

    def test_cancel_and_heartbeat(self):
        job, lease = self.leased()
        path = f'/oc/v1/jobs/{job["id"]}'
        self.assertEqual(self.call("POST", path + "/cancel", {})[0], 200)
        status, body = self.call("POST", path + "/heartbeat", {"protocolVersion": 1}, {"X-OpenCapture-Lease": lease})
        self.assertEqual(status, 200)
        self.assertTrue(body["cancelled"])
        self.assertEqual(self.upload(job, lease, "frame-0001.dng", b"test")[0], 409)

    def test_expiry_and_late_upload(self):
        job, lease = self.leased()
        self.state.jobs[job["id"]]["expires"] = 0
        self.assertEqual(self.upload(job, lease, "frame-0001.dng", b"test")[0], 409)
        self.assertEqual(self.state.jobs[job["id"]]["status"], "expired")
        self.assertEqual(self.call("POST", "/oc/v1/jobs", request())[0], 200)

    def test_preview_and_capabilities(self):
        self.assertEqual(self.call("GET", "/oc/v1/preview.jpg")[0], 404)
        self.assertEqual(self.call("POST", "/oc/v1/preview", b"not jpeg")[0], 415)
        self.assertEqual(self.call("POST", "/oc/v1/preview", b"\xff\xd8\xff\xd9")[0], 200)
        self.assertEqual(self.call("POST", "/oc/v1/capabilities", {"protocolVersion": 1, "formats": ["bayerRaw"]})[0], 200)

    def test_failure_persisted(self):
        job, lease = self.leased()
        failure = {"protocolVersion": 1, "status": "failed", "message": "Camera permission denied"}
        self.assertEqual(self.call("POST", f'/oc/v1/jobs/{job["id"]}/failure', failure, {"X-OpenCapture-Lease": lease})[0], 200)
        saved = json.loads((Path(self.temp.name) / job["id"] / "state.json").read_text())
        self.assertEqual(saved["status"], "failed")
        self.assertNotIn(lease, json.dumps(saved))

    def test_apply_controls_capability_and_readback(self):
        job = request()
        job["operation"] = "applyControls"
        self.assertEqual(self.call("POST", "/oc/v1/jobs", job)[0], 409)
        self.state.capabilities = {"protocolVersion": 1, "formats": ["bayerRaw"], "operations": ["capture", "applyControls"]}
        self.assertEqual(self.call("POST", "/oc/v1/jobs", job)[0], 200)
        lease = self.call("GET", "/oc/v1/lease")[1]["leaseToken"]
        self.assertEqual(self.upload(job, lease, "frame-0001.dng", b"test")[0], 409)
        result = {"protocolVersion": 1, "request": job, "camera": self.state.capabilities,
                  "deviceExposureSeconds": 0.05, "deviceISO": 110, "lensPosition": 0.48,
                  "whiteBalance": {"red": 1.2, "green": 1, "blue": 1.3}, "completedAt": "2026-11-07T12:00:00Z"}
        path = f'/oc/v1/jobs/{job["id"]}/controls-complete'
        headers = {"X-OpenCapture-Lease": lease}
        invalid = dict(result, lensPosition=2)
        self.assertEqual(self.call("POST", path, invalid, headers)[0], 400)
        self.assertEqual(self.call("POST", path, result, headers)[0], 200)
        self.assertEqual(self.call("POST", path, result, headers)[0], 200)
        self.assertEqual(self.call("GET", "/oc/v1/state")[1]["controls"], result)
        self.assertEqual(self.call("POST", path, dict(result, deviceISO=120), headers)[0], 409)
        self.assertFalse((Path(self.temp.name) / job["id"] / "manifest.json").exists())

    def test_cancelled_controls_cannot_complete(self):
        self.state.capabilities = {"operations": ["applyControls"]}
        job = request()
        job["operation"] = "applyControls"
        self.state.submit(job)
        lease = self.call("GET", "/oc/v1/lease")[1]["leaseToken"]
        self.call("POST", f'/oc/v1/jobs/{job["id"]}/cancel', {})
        result = {"protocolVersion": 1, "request": job, "camera": {}, "deviceExposureSeconds": 0.05,
                  "deviceISO": 100, "lensPosition": 0.5, "whiteBalance": {"red": 1, "green": 1, "blue": 1},
                  "completedAt": "2026-11-07T12:00:00Z"}
        self.assertEqual(self.call("POST", f'/oc/v1/jobs/{job["id"]}/controls-complete', result,
                                   {"X-OpenCapture-Lease": lease})[0], 409)


class ReleaseTests(unittest.TestCase):
    def test_rejects_simulator_and_builds_correct_feed(self):
        spec = importlib.util.spec_from_file_location("package_release", ROOT / "scripts/package_release.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temp:
            app = Path(temp) / "OpenCapture.app"
            app.mkdir()
            plist = {"DTPlatformName": "iphonesimulator", "CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "7",
                     "CFBundleIdentifier": "io.github.aron7awol.OpenCapture", "NSCameraUsageDescription": "Test camera use"}
            (app / "Info.plist").write_bytes(plistlib.dumps(plist))
            with self.assertRaises(ValueError):
                module.package(app, Path(temp) / "dist", "aron7awol/OpenCapture")
            plist["DTPlatformName"] = "iphoneos"
            (app / "Info.plist").write_bytes(plistlib.dumps(plist))
            if not (ROOT / "Resources/Assets.xcassets/AppIcon.appiconset/icon.png").exists():
                self.skipTest("Run scripts/make_icon.py before release tests")
            source = module.package(app, Path(temp) / "dist", "aron7awol/OpenCapture")
            version = source["apps"][0]["versions"][0]
            ipa = Path(temp) / "dist/OpenCapture.ipa"
            self.assertEqual(version["size"], ipa.stat().st_size)
            self.assertEqual(version["sha256"], hashlib.sha256(ipa.read_bytes()).hexdigest())
            self.assertEqual(source["apps"][0]["appPermissions"]["privacy"], {"NSCameraUsageDescription": "Test camera use"})
            self.assertIn("trusted private LAN", source["apps"][0]["localizedDescription"])
            self.assertIn("updated desktop controller", version["localizedDescription"])
            self.assertNotIn("paired remote capture", source["apps"][0]["localizedDescription"])


if __name__ == "__main__":
    unittest.main()
