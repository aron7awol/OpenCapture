"""Bounded, original-file iOS benchmarks. No display control or calibration.

Uses batch transfer to isolate camera timing from controller verdict latency.
Files and diagnostics are retained; software checks are not image qualification.
"""
import argparse
import hashlib
import http.client
import ipaddress
import json
import math
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlsplit
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'controller'))
from server import validate_request, canonical_request

MODES = ('standard', 'ios-raw-no-preview', 'ios-raw-bracket-pair', 'ios-raw-prepared')
NETWORKS = [ipaddress.ip_network(s) for s in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','169.254.0.0/16','127.0.0.0/8')]


def address(value):
    url = urlsplit(value if '://' in value else 'http://' + value)
    ip = ipaddress.IPv4Address(url.hostname)
    port = url.port or 8767
    if (url.scheme != 'http' or url.username or url.password or url.path not in ('','/') or
            url.query or url.fragment or not any(ip in n for n in NETWORKS) or not 1024 <= port <= 65535):
        raise ValueError('Use a trusted-LAN IPv4 phone address, without credentials or paths')
    return str(ip), port


def save(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n', encoding='utf-8')


def receipt_path(folder, receipt):
    name = receipt['name']
    if (not isinstance(name, str) or not re.fullmatch(r'frame-[0-9]{4}\.(dng|json|preview\.jpg)', name) or
            type(receipt.get('bytes')) is not int or not 1 <= receipt['bytes'] <= 134217728 or
            not re.fullmatch('[0-9a-f]{64}', receipt.get('sha256', ''))):
        raise ValueError('Malformed file receipt')
    path = folder / name
    if path.exists():
        raise ValueError('Duplicate file receipt')
    return path


class Peer:
    def __init__(self, target):
        self.host, self.port = address(target)
        self.session = str(uuid.uuid4())

    def request(self, method, path, body=None, *, receipt=None, destination=None):
        connection = http.client.HTTPConnection(self.host, self.port, timeout=20)
        headers = {'X-OpenCapture-Session': self.session, 'Connection': 'close'}
        if body is not None:
            body = json.dumps(body, allow_nan=False).encode()
            headers['Content-Type'] = 'application/json'
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            if response.status not in (200, 202):
                raise RuntimeError(f'Phone HTTP {response.status}: {response.read(2048).decode(errors="replace")}')
            size = response.getheader('Content-Length', '')
            limit = receipt['bytes'] if receipt else 1048576
            if not size.isdecimal() or int(size) > limit or response.getheader('Transfer-Encoding'):
                raise ValueError('Invalid response size')
            if receipt and int(size) != receipt['bytes']:
                raise ValueError('File size differs from receipt')
            remaining = int(size)
            if destination is None:
                data = response.read(remaining)
                if len(data) != remaining: raise ValueError('Truncated response')
                return data
            digest = hashlib.sha256()
            with destination.open('xb') as output:
                while remaining:
                    data = response.read(min(262144, remaining))
                    if not data: raise ValueError('Truncated file')
                    output.write(data); digest.update(data); remaining -= len(data)
            if digest.hexdigest() != receipt['sha256']:
                raise ValueError('Original hash differs from receipt')
        finally:
            connection.close()

    def json(self, method, path, body=None):
        return json.loads(self.request(method, path, body))


def assess(request, manifest, folder):
    """Metadata/timestamp checks only. Decoded RAW clipping/signal are separate."""
    if canonical_request(manifest['request']) != canonical_request(request):
        raise ValueError('Phone echoed a different request')
    frames = manifest['frames']
    if len(frames) != request['frameCount'] or [f['index'] for f in frames] != list(range(1, len(frames)+1)):
        raise ValueError('Incomplete or reordered burst')
    failures, timestamps, timings = [], [], []
    for frame in frames:
        index = frame['index']
        if frame.get('isBayerRAW') is not True or frame.get('isAppleProRAW', False): failures.append(f'{index}:wrong_format')
        for key, target, tolerance in (
            ('actualExposureSeconds',request['exposureSeconds'],max(.00001,request['exposureSeconds']*.02)),
            ('actualISO',request['iso'],max(1,request['iso']*.05)),
            ('deviceExposureSeconds',request['exposureSeconds'],max(.00001,request['exposureSeconds']*.02)),
            ('deviceISO',request['iso'],max(1,request['iso']*.05)),
            ('lensPosition',request['focusPosition'],.02)):
            value = frame.get(key)
            if not isinstance(value, (int,float)) or not math.isfinite(value) or abs(value-target)>tolerance:
                failures.append(f'{index}:{key}')
        for key, target in request['whiteBalance'].items():
            value = (frame.get('whiteBalance') or {}).get(key)
            if not isinstance(value,(int,float)) or not math.isfinite(value) or abs(value-target)>.1:
                failures.append(f'{index}:whiteBalance.{key}')
        timestamp = frame.get('timestampSeconds')
        if not isinstance(timestamp,(int,float)) or not math.isfinite(timestamp) or timestamp <= 0:
            failures.append(f'{index}:timestamp')
        else:
            if timestamps and timestamp-timestamps[-1] < request['exposureSeconds']*.98:
                failures.append(f'{index}:exposure_timestamp_gap')
            timestamps.append(timestamp)
        sidecar = json.loads((folder/frame['metadataFile']['name']).read_text())
        if sidecar.get('captureDiagnostics',{}).get('sampleIntegrity',{}).get('assessment') != 'consistent_native_buffer_and_file':
            failures.append(f'{index}:native_integrity')
        if request.get('captureExperiment') and sidecar.get('captureExperiment',{}).get('mode') != request['captureExperiment']:
            failures.append(f'{index}:experiment_evidence')
        timings.append(sidecar.get('captureTiming',{}))
    return dict(metadataChecksPassed=not failures, failures=failures, decodedRAWQuality='not_checked',
                sensorIntervalsSeconds=[b-a for a,b in zip(timestamps,timestamps[1:])],captureTiming=timings)


def run(args):
    target = Path(args.output)
    target.mkdir(parents=True, exist_ok=False)
    template = json.loads(Path(args.request).read_text(encoding='utf-8-sig'))
    # Accept a saved manifest, but never silently reinterpret automatic settings.
    template = template.get('request', template)
    if any(key in template for key in ('averaging','streaming','captureScheduling','focusProbe','captureExperiment')):
        raise ValueError('Use a plain fully manual request; do not reuse a mode/averaging/streaming request')
    template.update(id=str(uuid.uuid4()),includePreview=False,frameCount=args.frames)
    template['captureExperiment'] = MODES[1]
    validate_request(template)
    del template['captureExperiment']
    save(target/'template.json', template)
    peer, connected, active = Peer(args.phone), False, None
    results = []
    try:
        info = peer.json('GET','/oc/v1/info')
        save(target/'info-before.json', info)
        if info['capabilities'].get('platform') != 'ios': raise ValueError('This benchmark requires iOS')
        peer.json('POST','/oc/v1/connect',dict(protocolVersion=1,sessionID=peer.session,clientName='OpenCapture bounded iOS benchmark'))
        connected = True
        info = peer.json('POST','/oc/v1/camera',dict(cameraID=args.camera))
        save(target/'info-selected.json', info)
        capabilities = info['capabilities']
        for mode in args.modes:
            if mode != 'standard' and mode not in capabilities.get('captureExperiments',[]):
                raise ValueError(f'Phone does not advertise {mode}; install the benchmark build')
        for repetition in range(args.repeats):
            # Rotate order to distribute heating/time drift; no scene changes.
            order = args.modes[repetition % len(args.modes):] + args.modes[:repetition % len(args.modes)]
            for mode in order:
                folder = target/f'{repetition+1:02d}-{mode}'
                folder.mkdir()
                request = dict(template,id=str(uuid.uuid4()))
                if mode != 'standard': request['captureExperiment'] = mode
                validate_request(request)
                save(folder/'request.json', request)
                active = request['id']; prefix = '/oc/v1/jobs/'+active
                start = time.perf_counter()
                peer.json('POST','/oc/v1/jobs',request)
                deadline = time.monotonic() + 120 + args.frames*(template['exposureSeconds']*4+15)
                while True:
                    state = peer.json('GET',prefix)
                    save(folder/'job-state.json',state)
                    if state['status'] in ('failed','cancelled'): raise RuntimeError(state.get('message',state['status']))
                    if state['status'] == 'complete' and state.get('cameraReady',False): break
                    if time.monotonic() > deadline: raise TimeoutError('Bounded capture deadline exceeded')
                    time.sleep(.1)
                camera_seconds = time.perf_counter()-start
                data = peer.request('GET',prefix+'/result')
                digest = hashlib.sha256(data).hexdigest()
                if digest != state['resultSha256']: raise ValueError('Manifest hash mismatch')
                (folder/'manifest.json').write_bytes(data)
                manifest = json.loads(data)
                if manifest['camera']['cameraID'] != args.camera or manifest['camera'].get('deviceID') != capabilities.get('deviceID'):
                    raise ValueError('Camera identity changed')
                download_start = time.perf_counter()
                total_bytes = 0
                for frame in manifest['frames']:
                    for key in ('file','metadataFile','previewFile'):
                        receipt = frame.get(key)
                        if not receipt: continue
                        total_bytes += receipt['bytes']
                        if total_bytes > 4*1024**3: raise ValueError('Job exceeds byte limit')
                        path = receipt_path(folder,receipt)
                        peer.request('GET',prefix+'/files/'+receipt['name'],receipt=receipt,destination=path)
                download_seconds = time.perf_counter()-download_start
                report = assess(request,manifest,folder)
                report.update(mode=mode,repetition=repetition+1,jobID=active,
                    captureThroughCameraReadySeconds=camera_seconds,downloadSeconds=download_seconds,
                    totalThroughDownloadSeconds=time.perf_counter()-start,downloadBytes=total_bytes,
                    phonePerformance=manifest.get('performance'),transport='batch',
                    physicalMeasurementQualified=False)
                save(folder/'timing.json',report)
                results.append(report); save(target/'summary.json',results)
                # Exact originals and metadata are durable locally before phone cleanup.
                peer.json('POST',prefix+'/ack',dict(resultSha256=digest)); active = None
                print(f'{mode}: camera {camera_seconds:.3f}s, download {download_seconds:.3f}s, metadata={report["metadataChecksPassed"]}',flush=True)
                if not report['metadataChecksPassed']: raise ValueError('Metadata checks failed; stop and inspect retained files')
    except BaseException as error:
        save(target/'failure.json',dict(type=type(error).__name__,message=str(error),activeJob=active))
        if connected and active:
            try: peer.json('POST','/oc/v1/jobs/'+active+'/cancel',{})
            except Exception: pass
        raise
    finally:
        if connected:
            try: peer.json('POST','/oc/v1/disconnect',{})
            except Exception: pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phone',required=True)
    parser.add_argument('--camera',required=True,help='Exact camera ID from /oc/v1/info')
    parser.add_argument('--request',required=True,help='Verified explicit controls, as request JSON or saved manifest')
    parser.add_argument('--output',required=True,help='New archive directory; existing directories are refused')
    parser.add_argument('--frames',type=int,choices=range(1,19),default=6)
    parser.add_argument('--repeats',type=int,choices=range(1,6),default=3)
    parser.add_argument('--modes',nargs='+',choices=MODES,default=list(MODES))
    args = parser.parse_args()
    if len(args.modes) != len(set(args.modes)): parser.error('Duplicate modes')
    run(args)


if __name__ == '__main__':
    main()
