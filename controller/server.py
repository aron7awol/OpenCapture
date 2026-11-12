"""OpenCapture reference controller. Standard library only; trusted LAN use."""
from __future__ import annotations

import argparse
import hashlib
import hmac
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import uuid
import webbrowser

VERSION = 1
MAX_FILE = 128 * 1024 * 1024
MAX_JSON = 1024 * 1024
MAX_JOB_BYTES = 4 * 1024 * 1024 * 1024
FILE_RE = re.compile(r"frame-(\d{4})(\.dng|\.jpg|\.json|\.preview\.jpg)\Z")
JOB_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
HASH_RE = re.compile(r"[0-9a-f]{64}\Z")
TERMINAL = {"complete", "failed", "cancelled", "expired"}


class Invalid(ValueError):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def number(value, low, high):
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high


def parse_json(data):
    def finite_float(text):
        value = float(text)
        if not math.isfinite(value):
            raise Invalid("Non-finite JSON")
        return value

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise Invalid("Duplicate JSON key")
            result[key] = value
        return result

    try:
        return json.loads(data, object_pairs_hook=unique, parse_float=finite_float,
                          parse_constant=lambda value: (_ for _ in ()).throw(Invalid("Non-finite JSON")))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise Invalid("Malformed JSON") from error


def validate_request(request):
    required = {"protocolVersion", "id", "format", "quality", "frameCount", "settleMilliseconds", "context"}
    optional = {"exposureSeconds", "iso", "focusPosition", "whiteBalance", "operation"}
    if not isinstance(request, dict) or not required <= request.keys() or request.keys() - required - optional:
        raise Invalid("Missing or unknown request fields")
    if type(request["protocolVersion"]) is not int or request["protocolVersion"] != VERSION:
        raise Invalid("Unsupported protocol version")
    if not isinstance(request["id"], str) or not JOB_RE.fullmatch(request["id"]):
        raise Invalid("Request ID must be a lowercase UUID")
    if request["format"] not in ("bayerRaw", "appleProRaw", "jpeg") or request["quality"] not in ("speed", "quality"):
        raise Invalid("Unsupported format or priority")
    if request["format"] == "bayerRaw" and request["quality"] != "speed":
        raise Invalid("Bayer RAW requires speed priority")
    if request["format"] == "appleProRaw" and request["quality"] != "quality":
        raise Invalid("Apple ProRAW requires quality priority")
    if type(request["frameCount"]) is not int or not 1 <= request["frameCount"] <= 32:
        raise Invalid("Frame count must be 1...32")
    if request.get("operation") not in (None, "capture", "applyControls"):
        raise Invalid("Unsupported camera operation")
    if request.get("operation") == "applyControls" and request["frameCount"] != 1:
        raise Invalid("Apply controls requires frameCount 1 and saves no photo")
    if type(request["settleMilliseconds"]) is not int or not 0 <= request["settleMilliseconds"] <= 10000:
        raise Invalid("Invalid settling interval")
    exposure, iso = request.get("exposureSeconds"), request.get("iso")
    if (exposure is None) != (iso is None):
        raise Invalid("Manual exposure requires shutter time and ISO")
    if exposure is not None and (not number(exposure, 1e-9, 30) or not number(iso, 1, 204800)):
        raise Invalid("Invalid manual exposure")
    if request.get("focusPosition") is not None and not number(request["focusPosition"], 0, 1):
        raise Invalid("Invalid focus position")
    wb = request.get("whiteBalance")
    if wb is not None and (not isinstance(wb, dict) or set(wb) != {"red", "green", "blue"} or
                           not all(number(value, 1, 64) for value in wb.values())):
        raise Invalid("Invalid white balance gains")
    context = request["context"]
    if not isinstance(context, dict) or len(context) > 16 or any(
        not isinstance(key, str) or not isinstance(value, str) or len(key.encode()) > 64 or len(value.encode()) > 256
        for key, value in context.items()
    ):
        raise Invalid("Context is too large or not string-to-string")
    return request


def canonical_request(request):
    return {key: value for key, value in request.items() if value is not None}


def atomic_json(path, value):
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    delays = (.01, .02, .04, .08, .16, .32, .5, .5)
    try:
        temporary.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
        for attempt in range(len(delays) + 1):
            try:
                os.replace(temporary, path)
                return
            except PermissionError as error:
                # Windows readers/scanners can briefly omit FILE_SHARE_DELETE.
                # Keep the previous complete JSON visible while retrying only
                # the atomic rename; a persistent failure still propagates.
                if getattr(error, 'winerror', None) not in (5, 32, 33) or attempt == len(delays):
                    raise
                time.sleep(delays[attempt])
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass  # Preserve the original failure if cleanup is also blocked.


class State:
    def __init__(self, directory, token):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.token = token
        self.lock = threading.RLock()
        self.jobs = {}
        self.capabilities = None
        self.last_seen = 0
        self.preview = None
        self.preview_at = 0
        self.controls = None

    def expire(self):
        for job in self.jobs.values():
            if job["status"] == "leased" and job["expires"] < time.monotonic():
                job["status"] = "expired"
                self.persist(job)

    def persist(self, job):
        atomic_json(job["directory"] / "state.json", {
            "request": job["request"], "status": job["status"], "cancelRequested": job["cancelled"],
            "files": list(job["files"].values()), "message": job.get("message", "")})

    def submit(self, request):
        validate_request(request)
        with self.lock:
            self.expire()
            if request.get("operation") == "applyControls" and "applyControls" not in (self.capabilities or {}).get("operations", []):
                raise Invalid("Update the camera app: remote apply controls is not advertised", 409)
            if request["id"] in self.jobs:
                job = self.jobs[request["id"]]
                if canonical_request(job["request"]) == canonical_request(request):
                    return {"id": request["id"], "status": job["status"]}
                raise Invalid("ID already used for a different request", 409)
            if any(job["status"] not in TERMINAL for job in self.jobs.values()):
                raise Invalid("Finish or cancel the current request first", 409)
            if len(self.jobs) >= 1000:
                raise Invalid("Session job limit reached; restart the controller", 409)
            directory = self.directory / request["id"]
            if directory.exists():
                raise Invalid("Capture already exists on disk; use a new ID", 409)
            directory.mkdir()
            job = dict(request=request, status="queued", cancelled=False, files={}, uploading=set(),
                       directory=directory, lease=None, expires=0)
            self.jobs[request["id"]] = job
            atomic_json(directory / "request.json", request)
            self.persist(job)
            return {"id": request["id"], "status": "queued"}

    def job(self, job_id, lease=None, allow_complete=False):
        self.expire()
        job = self.jobs.get(job_id)
        if job is None:
            raise Invalid("Unknown capture", 404)
        if lease is not None:
            if not job["lease"] or not hmac.compare_digest(lease, job["lease"]):
                raise Invalid("Invalid lease", 403)
            if job["status"] != "leased" and not (allow_complete and job["status"] == "complete"):
                raise Invalid("Capture is no longer leased", 409)
        return job


class Controller(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, state, handler=None):
        self.state = state
        super().__init__(address, handler or Handler)


class Handler(BaseHTTPRequestHandler):
    server: Controller
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(30)
        self.bytes_read = 0

    def reply(self, status, data, content_type="application/json"):
        if not isinstance(data, bytes):
            data = json.dumps(data, allow_nan=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def auth(self):
        expected = "Bearer " + self.server.state.token
        given = self.headers.get("Authorization", "")
        if len(given) > 512 or not hmac.compare_digest(given.encode(), expected.encode()):
            raise Invalid("Pairing token required", 401)
        origin = self.headers.get("Origin")
        if origin and urlsplit(origin).netloc != self.headers.get("Host"):
            raise Invalid("Cross-origin request rejected", 403)

    def length(self, limit):
        if self.headers.get("Transfer-Encoding") or len(self.headers.get_all("Content-Length", [])) != 1:
            raise Invalid("Exactly one Content-Length is required", 411)
        try:
            length = int(self.headers["Content-Length"])
        except ValueError as error:
            raise Invalid("Invalid Content-Length") from error
        if not 0 <= length <= limit:
            raise Invalid("Body too large", 413)
        return length

    def body(self, limit=MAX_JSON):
        length = self.length(limit)
        data = self.rfile.read(length)
        self.bytes_read += len(data)
        if len(data) != length:
            raise Invalid("Incomplete body")
        return data

    def json_body(self):
        if self.headers.get_content_type() != "application/json":
            raise Invalid("Expected application/json", 415)
        return parse_json(self.body())

    def execute(self):
        try:
            self.route()
        except Invalid as error:
            # Drain only small rejected bodies so Windows does not reset the response.
            try:
                remaining = int(self.headers.get("Content-Length", "0")) - self.bytes_read
                if 0 < remaining <= 65536:
                    self.connection.settimeout(0.25)
                    self.rfile.read(remaining)
                    self.connection.settimeout(30)
            except (ValueError, OSError):
                pass
            self.reply(error.status, {"protocolVersion": VERSION, "error": str(error)})
        except (OSError, TimeoutError):
            self.close_connection = True
        except Exception:
            self.reply(500, {"error": "Controller error; originals are retained"})

    do_GET = execute
    do_POST = execute
    do_PUT = execute

    def route(self):
        state = self.server.state
        path = urlsplit(self.path).path
        if self.command == "GET" and path == "/":
            self.reply(200, Path(__file__).with_name("control.html").read_bytes(), "text/html; charset=utf-8")
            return
        self.auth()
        if self.command == "GET" and path == "/oc/v1/state":
            with state.lock:
                state.expire()
                self.reply(200, {"protocolVersion": VERSION, "capabilities": state.capabilities,
                    "controls": state.controls,
                    "lastSeenSecondsAgo": time.time() - state.last_seen if state.last_seen else None,
                    "previewAgeSeconds": time.time() - state.preview_at if state.preview_at else None,
                    "jobs": [{"request": job["request"], "status": job["status"], "files": len(job["files"]),
                              "message": job.get("message", "")} for job in list(state.jobs.values())[-100:]]})
            return
        if self.command == "GET" and path == "/oc/v1/preview.jpg":
            with state.lock:
                if state.preview is None:
                    raise Invalid("No preview", 404)
                self.reply(200, state.preview, "image/jpeg")
            return
        if self.command == "POST" and path == "/oc/v1/capabilities":
            data = self.json_body()
            if not isinstance(data, dict) or data.get("protocolVersion") != 1 or not isinstance(data.get("formats"), list):
                raise Invalid("Invalid capabilities")
            with state.lock:
                state.capabilities = data
                state.last_seen = time.time()
            self.reply(200, {"protocolVersion": VERSION})
            return
        if self.command == "POST" and path == "/oc/v1/preview":
            data = self.body(2 * 1024 * 1024)
            if not data.startswith(b"\xff\xd8") or not data.endswith(b"\xff\xd9"):
                raise Invalid("Expected JPEG preview", 415)
            with state.lock:
                state.preview, state.preview_at, state.last_seen = data, time.time(), time.time()
            self.reply(200, {"protocolVersion": VERSION})
            return
        if self.command == "POST" and path == "/oc/v1/jobs":
            self.reply(200, state.submit(self.json_body()))
            return
        if self.command == "GET" and path == "/oc/v1/lease":
            with state.lock:
                state.expire()
                state.last_seen = time.time()
                queued = next((job for job in state.jobs.values() if job["status"] == "queued"), None)
                if queued:
                    queued.update(status="leased", lease=secrets.token_urlsafe(32), expires=time.monotonic() + 120)
                    state.persist(queued)
                    self.reply(200, {"protocolVersion": VERSION, "request": queued["request"], "leaseToken": queued["lease"]})
                else:
                    self.reply(200, {"protocolVersion": VERSION})
            return
        parts = path.split("/")
        if len(parts) not in (6, 7) or parts[1:4] != ["oc", "v1", "jobs"] or not JOB_RE.fullmatch(parts[4]):
            raise Invalid("Unknown endpoint", 404)
        job_id, action = parts[4:6]
        if action == "cancel" and self.command == "POST" and len(parts) == 6:
            self.body(1024)
            with state.lock:
                job = state.job(job_id)
                if job["status"] not in TERMINAL:
                    job["cancelled"] = True
                    if job["status"] == "queued":
                        job["status"] = "cancelled"
                    state.persist(job)
            self.reply(200, {"protocolVersion": VERSION})
            return
        lease = self.headers.get("X-OpenCapture-Lease", "")
        if not lease or len(lease) > 128:
            raise Invalid("Lease required", 403)
        with state.lock:
            job = state.job(job_id, lease, allow_complete=action in ("files", "complete", "controls-complete"))
        if action == "heartbeat" and self.command == "POST" and len(parts) == 6:
            self.json_body()
            with state.lock:
                job = state.job(job_id, lease)
                job["expires"] = time.monotonic() + 120
                state.last_seen = time.time()
                self.reply(200, {"protocolVersion": VERSION, "cancelled": job["cancelled"]})
        elif action == "files" and self.command == "PUT" and len(parts) == 7:
            self.upload(job_id, lease, parts[6])
        elif action == "complete" and self.command == "POST" and len(parts) == 6:
            self.complete(job_id, lease, self.json_body())
        elif action == "controls-complete" and self.command == "POST" and len(parts) == 6:
            self.complete_controls(job_id, lease, self.json_body())
        elif action == "failure" and self.command == "POST" and len(parts) == 6:
            failure = self.json_body()
            if not isinstance(failure, dict) or failure.get("protocolVersion") != 1 or failure.get("status") not in {"failed", "cancelled"} or not isinstance(failure.get("message"), str):
                raise Invalid("Malformed failure")
            with state.lock:
                job = state.job(job_id, lease)
                job.update(status=failure["status"], message=failure["message"][:256])
                state.persist(job)
            self.reply(200, {"protocolVersion": VERSION})
        else:
            raise Invalid("Unknown endpoint", 404)

    def upload(self, job_id, lease, name):
        state = self.server.state
        match = FILE_RE.fullmatch(name)
        if not match:
            raise Invalid("Invalid filename")
        length = self.length(MAX_FILE)
        digest = self.headers.get("X-Content-SHA256", "")
        if not HASH_RE.fullmatch(digest):
            raise Invalid("SHA-256 required")
        with state.lock:
            job = state.job(job_id, lease, allow_complete=True)
            if job["request"].get("operation") == "applyControls":
                raise Invalid("Control operations cannot upload photos", 409)
            if job["cancelled"]:
                raise Invalid("Capture cancelled", 409)
            if not 1 <= int(match[1]) <= job["request"]["frameCount"]:
                raise Invalid("Invalid frame index")
            expected_image = ".dng" if job["request"]["format"] in ("bayerRaw", "appleProRaw") else ".jpg"
            if match[2] not in {expected_image, ".json", ".preview.jpg"}:
                raise Invalid("File does not match requested format")
            previous = job["files"].get(name)
            if previous and (previous["sha256"] != digest or previous["bytes"] != length):
                raise Invalid("Conflicting duplicate file", 409)
            if job["status"] == "complete" and previous is None:
                raise Invalid("Completed capture is immutable", 409)
            if name in job["uploading"]:
                raise Invalid("File already uploading", 409)
            if sum(file["bytes"] for file in job["files"].values()) + length > MAX_JOB_BYTES:
                raise Invalid("Capture storage limit exceeded", 413)
            if shutil.disk_usage(state.directory).free < length + 100 * 1024 * 1024:
                raise Invalid("Insufficient controller disk space", 507)
            job["uploading"].add(name)
            temporary = job["directory"] / (name + "." + secrets.token_hex(8) + ".part")
        try:
            hash_state = hashlib.sha256()
            with temporary.open("xb") as stream:
                remaining = length
                deadline = time.monotonic() + 180
                while remaining:
                    if time.monotonic() > deadline:
                        raise Invalid("Upload deadline exceeded", 408)
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise Invalid("Truncated upload")
                    remaining -= len(chunk)
                    self.bytes_read += len(chunk)
                    hash_state.update(chunk)
                    stream.write(chunk)
            if hash_state.hexdigest() != digest:
                raise Invalid("Content hash mismatch", 422)
            media_type = {".dng": "image/dng", ".jpg": "image/jpeg", ".preview.jpg": "image/jpeg", ".json": "application/json"}[match[2]]
            receipt = {"name": name, "bytes": length, "sha256": digest, "mediaType": media_type}
            with state.lock:
                job = state.job(job_id, lease, allow_complete=True)
                if job["cancelled"]:
                    raise Invalid("Capture cancelled", 409)
                if previous is None:
                    os.replace(temporary, job["directory"] / name)
                    job["files"][name] = receipt
                    state.persist(job)
            self.reply(200, receipt)
        finally:
            with state.lock:
                job["uploading"].discard(name)
            temporary.unlink(missing_ok=True)

    def complete_controls(self, job_id, lease, result):
        required = {"protocolVersion", "request", "camera", "deviceExposureSeconds", "deviceISO",
                    "lensPosition", "whiteBalance", "completedAt"}
        if not isinstance(result, dict) or set(result) != required or type(result["protocolVersion"]) is not int or result["protocolVersion"] != 1:
            raise Invalid("Malformed controls result")
        if not number(result["deviceExposureSeconds"], 1e-9, 30) or not number(result["deviceISO"], 1, 204800) or not number(result["lensPosition"], 0, 1):
            raise Invalid("Invalid device readings")
        wb = result["whiteBalance"]
        if not isinstance(wb, dict) or set(wb) != {"red", "green", "blue"} or not all(number(v, 1, 64) for v in wb.values()):
            raise Invalid("Invalid white balance readings")
        if not isinstance(result["camera"], dict) or not isinstance(result["completedAt"], str) or len(result["completedAt"]) > 64:
            raise Invalid("Invalid camera or completion time")
        state = self.server.state
        with state.lock:
            job = state.job(job_id, lease, allow_complete=True)
            if job["cancelled"] or job["request"].get("operation") != "applyControls":
                raise Invalid("Not an active controls request", 409)
            if canonical_request(validate_request(result["request"])) != canonical_request(job["request"]):
                raise Invalid("Controls request mismatch", 409)
            path = job["directory"] / "controls.json"
            if job["status"] == "complete":
                if parse_json(path.read_bytes()) != result:
                    raise Invalid("Completed controls result is immutable", 409)
            else:
                atomic_json(path, result)
                job["status"] = "complete"
                state.controls = result
                state.persist(job)
        self.reply(200, {"protocolVersion": VERSION})

    def complete(self, job_id, lease, manifest):
        state = self.server.state
        with state.lock:
            job = state.job(job_id, lease, allow_complete=True)
            if job["cancelled"] or job["uploading"]:
                raise Invalid("Cancelled or still uploading", 409)
            if not isinstance(manifest, dict) or set(manifest) != {"protocolVersion", "request", "camera", "frames", "startedAt", "completedAt", "processing"}:
                raise Invalid("Malformed manifest")
            validate_request(manifest["request"])
            if manifest["protocolVersion"] != 1 or canonical_request(manifest["request"]) != canonical_request(job["request"]):
                raise Invalid("Manifest request mismatch", 409)
            frames = manifest["frames"]
            if not isinstance(frames, list) or len(frames) != job["request"]["frameCount"]:
                raise Invalid("Frame count mismatch")
            referenced = set()
            for index, frame in enumerate(frames, 1):
                if not isinstance(frame, dict) or frame.get("index") != index or frame.get("isBayerRAW") is not (job["request"]["format"] == "bayerRaw"):
                    raise Invalid("Frame identity mismatch")
                if frame.get("isAppleProRAW", False) is not (job["request"]["format"] == "appleProRaw"):
                    raise Invalid("Apple ProRAW identity mismatch")
                if not all(type(frame.get(key)) is int and 1 <= frame[key] <= 20000 for key in ("width", "height")):
                    raise Invalid("Invalid frame dimensions")
                if not all(number(frame.get(key), 0, 1e12) for key in ("timestampSeconds", "deviceExposureSeconds", "deviceISO", "lensPosition")):
                    raise Invalid("Missing capture-result metadata")
                for key in ("actualExposureSeconds", "actualISO"):
                    if frame.get(key) is not None and not number(frame[key], 0, 1e12):
                        raise Invalid("Invalid EXIF metadata")
                suffix = ".dng" if job["request"]["format"] in ("bayerRaw", "appleProRaw") else ".jpg"
                expected = {"file": f"frame-{index:04d}{suffix}", "metadataFile": f"frame-{index:04d}.json"}
                if frame.get("previewFile") is not None:
                    expected["previewFile"] = f"frame-{index:04d}.preview.jpg"
                for field, name in expected.items():
                    record = frame.get(field)
                    if not isinstance(record, dict) or record.get("name") != name or record != job["files"].get(name):
                        raise Invalid("Manifest references missing or mismatched file", 409)
                    referenced.add(name)
            if referenced != job["files"].keys():
                raise Invalid("Manifest omits uploaded files", 409)
            destination = job["directory"] / "manifest.json"
            if job["status"] == "complete":
                if parse_json(destination.read_bytes()) != manifest:
                    raise Invalid("Conflicting completion", 409)
            else:
                atomic_json(destination, manifest)
                job["status"] = "complete"
                state.persist(job)
            self.reply(200, {"protocolVersion": VERSION, "id": job_id, "status": "complete"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", default=8766, type=int)
    parser.add_argument("--data", type=Path, default=Path(__file__).parent.parent / "captures")
    parser.add_argument("--open-browser", action="store_true")
    args = parser.parse_args()
    address = ipaddress.ip_address(args.listen)
    if address.version != 4 or not (address.is_private or address.is_unspecified):
        parser.error("Use loopback or an explicitly chosen trusted-LAN IPv4 interface")
    settings = args.data.parent / "controller-settings.json"
    if settings.exists():
        token = parse_json(settings.read_bytes())["token"]
    else:
        settings.parent.mkdir(parents=True, exist_ok=True)
        token = secrets.token_urlsafe(32)
        atomic_json(settings, {"token": token})
    if not isinstance(token, str) or not 24 <= len(token) <= 256:
        parser.error("Invalid saved controller token")
    server = Controller((args.listen, args.port), State(args.data, token))
    print(f"OpenCapture controller: http://{args.listen}:{server.server_port}", flush=True)
    print(f"Pairing token: {token}", flush=True)
    print(f"Captures: {args.data.resolve()}", flush=True)
    if not address.is_loopback:
        print("Trusted LAN only: HTTP is not encrypted. Do not port-forward this server.", flush=True)
    if args.open_browser:
        browser_host = "127.0.0.1" if address.is_unspecified else args.listen
        webbrowser.open(f"http://{browser_host}:{server.server_port}/#token={token}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
