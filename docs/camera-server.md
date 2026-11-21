# Foreground Camera Server v1

### iOS Session Diagnostics (November 20 Development)

iOS job states now optionally include `cameraDiagnostics` (schemaVersion 1,
platform `ios`, policy `ios-session-v1`). Existing state/result/file routes and
their ownership guards are unchanged. The diagnostics are available even when
preparation fails before a photo exists, and survive that job's file cleanup.
They do not advertise the Android `persistent-camera-v1` mode.

Each snapshot includes an allowlisted `request` (string values; never opaque
context), `startedSequence`, up to 32 `events`, eight `priorEvents`, and a
separately preserved `firstFault`. Events contain sequence, monotonic `elapsedNs`,
wall `unixMs`, type, optional requestID and bounded string fields. Each encoded
event is at most 2048 bytes; a snapshot stays below 96 KiB. The active job and
eight completed snapshots are kept in memory. They are not a crash-persistent
archive and can be lost on process exit. Existing desktop clients may ignore
the optional field; clients saving it should retain it before starting many
more jobs. Previous-job context is explicitly separated from current events.

Evidence includes AVFoundation runtime NSError domain/code and up to three
underlying errors (no userInfo or localized descriptions), interruption reason,
session start/stop notifications, explicit stop/cancel causes, requested and
actual controls, modes/adjusting flags, reuse decisions, custom-exposure
submission/completion, settling samples and photo lifecycle. Late callbacks
carry their original request ID and cannot become another job's first fault.
Notification logging uses a small lock only; it does not wait for the camera
queue, capture extra images, write files or upload independently.

This build adds evidence, not a new exposure policy or an automatic retry.
Physical automatic-exposure transition recovery remains unverified.

### Experimental Short Preview Exposure (November 19 Development)

New Android builds advertise `sequential-short-preview` in captureSchedulingModes.
The optional request value forces sequential RAW with continuous preview, capped
at `min(requested RAW exposure, 0.2 s)`. ISO, focus, WB, color transform, crop and
processing policy stay locked. RAW requests and per-still verification retain
the full original exposure; preview pixels are never measurement input.

Unreleased November 20 setup also caps repeating previews while applying an
explicit exposure and while verifying the manual lock. Automatic exposure
metering remains automatic until an exposure has been selected. When the locked
exposure exceeds the cap, one separately tagged, preview-only full-exposure
request verifies the requested controls while repeating preview stays short.
This avoids a queue of long repeating frames. It takes no RAW photo and returns
a genuine full-exposure result from applyControls, preserving the existing
controller contract. Unrelated preview results cannot satisfy that request;
exposure/ISO/focus/WB tolerances and other-state comparisons remain enforced.
Verified reuse and aiming preview also keep the cap. This cannot retroactively
shorten frames already queued by another mode. Actual still mismatches fail normally;
there is no silent retry in another mode or relaxed tolerance. Diagnostics record
requested preview exposure separately and actual preview/RAW results when delivered.
Setup records `previewSetupPolicy:short-repeating-single-full-readback-v1`,
`requestedSetupPreviewExposureNs`, and separate `fullExposureReadback*` request,
result, tag-match, control-check, state-difference and timing evidence. A short
manual-settle timestamp is not a full-exposure verification timestamp.
The feature is experimental, default off, exact-capability-gated, and unavailable
on iOS/older Android clients. Software tests are not physical compatibility proof.

### Experimental Queued Stills With Preview (November 20 Development)

New Android builds advertise `queued-preview` in captureSchedulingModes. The
optional request value keeps the manually locked preview running at the full
requested exposure, as `sequential-preview` does, and submits up to four RAW
stills before the earlier ones finish (`queued-ahead-v1`). Camera2 serves
single captures ahead of the repeating request, so queued stills are meant to
expose back to back with no preview frame between them. With one still at a
time, a saved Xiaomi run showed each still waiting behind about six preview
frames as long as the still itself.

The limit of four is the RAW reader's buffer count: a still is counted from
submission until its image is closed, so no more than four buffers are held.
Stills are processed, verified, written and published strictly in request
order; controls, per-still verification, timing integrity and sample checks
are unchanged. With streaming, each still is reserved first and at most four
attempts may be unjudged; accepted plus pending attempts still never exceed
the target. Phone averaging keeps one still at a time under this mode. A
failure or cancellation aborts the outstanding requests as before.

Sidecars record `exposureOverlapPolicy:"queued-ahead-v1"`, `queuedAheadWindow`
and `unfinishedStillsAtSubmission`; `raw_preview_policy` records the requested
and actual window. Frame numbers of consecutive stills show whether preview
frames ran between them. The mode is experimental, default off, exact-
capability-gated and unavailable on iOS and older Android clients. Whether a
given phone runs queued stills back to back, and whether the first still after
preview matches the following ones, is unverified on hardware.

Development Android info/state includes optional boolean `cameraPreviewReady`:
the current preview is configured and has delivered a result since camera
close/reopen. It is false during capture/recovery; it is not a signal/noise or
exposure-quality assessment. It is distinct from `session.cameraReady`, which
means the capture writer has drained and no lens switch is in progress. A
controller restoring controls after failure must wait for both before submitting
another job, otherwise that new writer can block camera recovery. Always verify
device/lens identity on fresh state replies. Older clients omit the field; do
not infer preview readiness from the writer flag alone. iOS is unchanged.

Development Android diagnostics: capabilities may advertise
`captureSchedulingModes:["sequential","sequential-preview"]`. Only then may a controller add
`captureScheduling:"sequential"` to a capture or applyControls request. It
disables original-exposure lookahead; streaming transport and exact-count
replacement policy are unchanged. Absent field preserves existing behavior.
The optional `sequential-preview` value also disables lookahead, but keeps the
manually locked repeating preview running throughout RAW still capture, DNG
writing, inspection and streaming-verdict waits. Development source can reuse
verified identical manual settings in the same healthy session, including a
completed controls-only manual preview readback. Changed/automatic controls
still require fresh setup. It does not re-meter/refocus between frames.
Preview results never enter the still-result
queue or become measurement pixels. Capability-gate the exact requested value:
older Android builds advertising only `sequential` do not support this mode.
It applies to original, streamed and averaged Bayer capture; exact usable counts,
2*N attempt limits and cancellation remain unchanged. `applyControls` accepts
the mode for consistent controller requests but captures no RAW frames.
Other values are invalid. iOS does not currently advertise this extension.

Per-frame timing records `repeatingPreviewDuringStill`, cumulative
`previewResultsAtSubmission` / `previewResultsAtCompletion`, and (if available)
`latestPreviewDuringStills` actual settings/timestamps. Operation diagnostics
record `raw_preview_policy`, `preview_during_raw_first_result` and
`raw_preview_summary`, including counts on failure. The flag is requested
behavior; delivered preview callbacks are separate evidence, not proof that a
particular phone avoids its camera-driver watchdog.

Failed Android job states may include `cameraDiagnostics` (schemaVersion 1):
the sanitized operation request, start sequence, first fault and a bounded
128-event timeline with monotonic/wall timestamps. Camera2 error numbers/names,
still failures, request exposure/ISO, received frame controls and lifecycle
events help distinguish original faults from recovery/cancellation symptoms.
The first fault survives event-ring eviction. The failed-job snapshot survives
transient original-file cleanup but ends with the foreground server session.
The persistent extension below retains separate history across process
restarts. Direct info's `camera2Diagnostics.runtime` exposes recent
events without taking a photograph. Neither field contains images or opaque
controller context; neither is a substitute for system crash logs.

### Persistent Android Diagnostics (Development)

Android advertises top-level `diagnosticModes:["persistent-camera-v1"]` in
info/state replies. After connecting, GET `/oc/v1/diagnostics` with the session
header retrieves a schemaVersion-1 bundle: `mode`, `deviceID`, `currentProcessID`,
`records`, `exitInfo`, and nullable `storageError`. It uses the same LAN/Host/
Origin/session guards as other private endpoints. Older apps and iOS do not
advertise this mode; controllers must not require it to capture.

Each record has a UUID `recordID`, `updatedUnixMs`, `process` (UUID `id`, PID,
start time, app version/build, model and SDK), and `cameraDiagnostics` in the
existing snapshot format. `request.id` identifies the original camera job;
controller context, credentials and image pixels are not stored. A process ID
change is not proof of a crash. `operation_completed` marks capture/control
completion, not completion of file transfer or a controller's measurement.

An app-private `camera-diagnostics` folder retains at most twelve recent
operation/startup snapshots, at most 96 KiB each plus one replacement temporary
file. New operations do not overwrite the previous operation's first fault.
One background writer coalesces pending snapshots per operation, with a bounded
12-entry queue, syncs each file and atomically replaces it. Camera callbacks
never wait for disk I/O. An abrupt kill can lose the last queued/in-progress
write, but previously committed snapshots survive; corrupt/unfinished files do
not prevent reading other records. Stop, session expiry, capture ACK and original
cleanup do not delete these records. Uninstall/clear-app-data does.

An uncaught JVM exception adds a bounded class/stack record (no exception
message), attempts a 1.5-second flush, then delegates to Android's existing
crash handler. Native crashes, forced kills, power loss and exhausted storage
may prevent that final record. Android 11+ supplies up to eight available own-
package `ApplicationExitInfo` entries (PID, timestamp, numeric reason/status and
importance); older APIs report `unsupported`, failed queries `unavailable`.
No reason is inferred when Android supplies none. Exit history is sampled at
startup and is not a full logcat, vendor log, or native tombstone export.

Controllers download on connection/reconnection, validate device identity and
limits (2 MiB HTTP ceiling), and preserve evidence before commencing new work.
Retrieval is optional and must not prevent connection on failure. Reading does
not delete records or resume captures. Any prior operation can be linked only
to the matching local capture ID/device, never the currently active run merely
because it was connected when diagnostics were downloaded.

OpenCapture opens a camera server on the trusted Wi-Fi LAN while foregrounded.
There is no account, pairing prompt, stored controller address or local gallery.
A desktop controller discovers/selects the phone and owns capture settings and lens choice.
The existing protocol-1 capture request, controls result, original-file receipts
and capture manifest are unchanged. This is a new transport, not a new image domain.

## Discovery And Session

Advertise `_oc-camera._tcp.local.` with TXT `protocol=1`, `role=camera`,
`auth=none`, and an app-install-scoped `deviceID`. Default TCP port is 8767.
Bind a private Wi-Fi/LAN IPv4 address. Reject non-private peers, foreign browser
origins, non-local Host values, oversized bodies and unsupported methods.
Plain HTTP provides no confidentiality against other LAN participants. This
mode must not be exposed through port forwarding or used on untrusted networks.

GET `/oc/v1/info` is public on that LAN and returns `protocolVersion:1`,
`role:"camera"`, `deviceID`, `name`, `capabilities` and `cameras` (objects with
`id` and `name`). Capabilities describe only the currently configured lens.

Android 0.4.7/build 15 adds an optional `camera2Diagnostics` object to this
GET only, not to heartbeats or lens selection. Its `cameras` array lists
public rear camera IDs, including non-selectable cameras. Match `cameraID`
to `capabilities.cameraID`. Entries list reported Camera2 characteristics:
RAW sizes, Bayer/RAW/manual sensor/manual post-processing support, missing
OpenCapture requirements, AE modes/lock availability, lock request and result
keys, exposure time and ISO result keys, and compensation limits. Hardware
level, CFA and AE modes are Android enum values; null means unknown.
An unreadable ID has an error instead of inferred capabilities. No camera is
opened or configured. These are declarations, not verified still results:
`rawExposureLockTested: false` means this endpoint performs no physical test.

Android 0.4.5/build 13 and iOS 0.4.4 also report
`batteryPercent` (integer 0..100) and `batteryCharging` (boolean; full while
connected counts as charging). Null or absent means the OS reading is unknown,
not zero percent. Both fields are optional for compatibility with older apps.
They are fresh OS readings in `/oc/v1/info` and `/oc/v1/state`, independent of
camera controls and safe to poll during captures. Windows can display these
from its existing heartbeat; no new capture or battery endpoint is needed.

POST `/oc/v1/connect` accepts exactly `protocolVersion`, `sessionID` (a new
lowercase UUID generated by the PC), and `clientName` (1..80 characters).
The same ID is idempotent; another ID receives 409 while the session is alive.
This automatic reservation is not authentication/pairing. Subsequent requests
carry `X-OpenCapture-Session`. A valid request refreshes a 120-second idle lease.
GET `/oc/v1/state` is the heartbeat and returns current info and job state.
The PC sends heartbeats during slow captures/downloads. Disconnect, foreground
exit, or idle expiry cancels work and clears only this session's staging files.
An interrupted/restarted session cannot silently replay an old capture ID.

Android 0.4.6 and iOS 0.4.5 preserve the same reservation and staging when only the
foreground Wi-Fi listener is rebound. This does not reset or extend the
120-second idle lease without actual controller traffic. Stop/backgrounding
still end the session. A controller may reconnect idempotently with its existing
session UUID, verify the same device/lens and retry the same receipts/ACK; it
must not use a new job to discard an unacknowledged result. Android source also
keeps the listener during camera loss and bounds automatic reopen attempts.
Camera callbacks and delayed retries are scoped to their opening generation;
Stop/background, surface loss and explicit lens selection invalidate old work.
Listener rebinding during an existing session does not wait for the aiming
preview, which pauses during a RAW burst. Physical-device verification remains
required; recovery does not survive app termination or an expired session.

POST `/oc/v1/disconnect` releases the reservation. POST `/oc/v1/camera` accepts
`{"cameraID":"..."}` and returns fresh info after reconfiguration. Unknown IDs
fail; no silent fallback. Lens changes are refused during capture or pending
transfer. A controller should also block phone/lens changes throughout any active
multi-capture operation, including pauses. New lens capabilities replace old
ones; lens-specific exposure/focus/mode settings must be checked again.

Android 0.4.4 keeps the compatible RAW+preview session configured
between jobs. Both platforms may reuse fully specified unchanged controls
after checking actual locked readings; auto/changed controls still prepare.
Requested scene-settling time is always retained. Failure, camera closure or
lens reconfiguration invalidates reuse. iOS 0.4.3 advertises optional
per-photo thumbnails as described in [the request contract](protocol.md#request).
Android 0.4.5/build 13 also skips the redundant manual-preview
settling cycle after a verified completed still in the same configuration.
`applyControls` continues to return fresh readback. See
[setup diagnostics](raw-diagnostics.md#android-045-verified-manual-fast-path-development).

## Jobs And Files

The optional [original-streaming extension](original-streaming.md) adds
published receipts during capture, ordered controller verdicts and a frozen
selection. It requires the advertised capability and explicit request; ordinary
whole-job clients keep the lifecycle below.

- POST `/oc/v1/jobs`: the unchanged capture/applyControls request; 202 with state.
- GET `/oc/v1/jobs/<id>`: `protocolVersion`, `id`, `status`, `message`, and,
  once complete, `resultSha256`. States: running, complete, failed, cancelled,
  acknowledged. Reposting an identical request ID returns state, never recaptures;
  a changed request with the same ID returns 409.
- GET `/oc/v1/jobs/<id>/result`: exact UTF-8 JSON capture manifest or controls
  result. SHA-256 must match `resultSha256` from job state.
- GET `/oc/v1/jobs/<id>/files/<name>`: stream only originals/sidecars explicitly
  referenced by that result; exact Content-Length and original bytes.
- POST `/oc/v1/jobs/<id>/ack`: `{"resultSha256":"..."}` after the controller has
  verified every file's length/hash and committed the complete result locally.
  Delete phone image files, retain an acknowledged ID/result-hash tombstone
  within the session. Repeated matching acknowledgement succeeds.
- POST `/oc/v1/jobs/<id>/cancel`: cooperative cancellation, then staging cleanup
  after the capture/file writer relinquishes its files.

JSON request bodies are at most 16 KiB; result JSON at most 1 MiB, preview JPEG
at most 2 MiB, each original at most 128 MiB, and one job at most 4 GiB. Only one
unacknowledged job is allowed, with at most 1000 IDs per session. No generic
filesystem, URL-fetch, arbitrary upload destination or archive enumeration API.
GET `/oc/v1/preview.jpg` returns the latest processed aiming JPEG, not measurement.

Temporary originals exist only to complete or retry transfer in this active
session. Interrupted downloads retry the same bytes/ID; they never retake photos
under that ID or relabel old measurements as fresh. A terminal capture/format/I/O
exception marks the job failed and cleans its staging; there is no failed-job
archive download. This differs from a completed result whose transfer is being
retried, and from an averaging `aggregation.status:insufficient` result: the
latter still completes transport so the controller can save its declared
diagnostic evidence before ACK, but contains no usable average. Session expiry
cancels capture before deleting files. Old pre-server archives are not silently
erased by this change. The controller is responsible
for retaining received originals and diagnostics after acknowledging receipt.

## Minimal Phone UI

One bounded, aspect-fit live preview, camera/connection/job status, a stop/resume
button and About (version/build, licenses, update action). The toolbar is below
the feed in portrait and on the right in wide landscape. A gray one-physical-
pixel border and horizontal/vertical bisectors mark the actual fitted image,
not letterboxing. These guides never appear in originals or preview JPEGs.
No Camera/Captures/Connection tabs, local shutter,
exposure forms, gallery or pairing UI. Keep awake while serving. Foreground exit
ends the session; reopening starts a fresh server. No background camera service.
