# Original streaming v1

Optional extension to the foreground camera-server transport. The generic
phone permits transfer of an earlier finalized original during the next
exposure. Current source also supports [bounded exposure overlap](exposure-overlap.md)
with at most two unfinished photos. The controller owns frame selection. No
camera/display calibration or scene interpretation is performed by this mode.

## Negotiation and publication

The selected lens advertises `captureStreamingModes:["originals-v1"]`. A
capture request may include `"streaming":{"mode":"originals-v1"}`. This is
incompatible with `averaging` and `operation:"applyControls"`. An older phone
without the capability continues to use whole-job transfer. Controls settle
and lock once per job, and all original bytes and actual controls are preserved.

GET `/oc/v1/jobs/<id>` adds:

```json
{"streaming":{"mode":"originals-v1","frames":[],"verdictCount":0,"frozen":false},"cameraReady":false}
```

`frames` is an append-only sequence of the existing full frame records. Its
original, sidecar and optional preview are finalized before publication; their
ordinary length/SHA-256 receipts never change. Existing file GET routes may
serve those published names while the job is still running. No partial file or
arbitrary path is exposed. Re-downloads use the same bytes.

There may be at most two unjudged attempts, counting the in-flight exposure:
the frame being transferred and one capture ahead. Current source reserves each
exposure before submission; a new exposure starts only when the reserved count
minus verdict count is less than two. Unpublished photos consume slots too.
The historical sequential implementation used published count before submission;
that was valid only with its single unfinished photo. Capture order
and verdict order are sequential. At most 2*N total attempts are allowed, with
the existing per-file, manifest and 4 GiB job limits.

## Verdict and frozen result

After verifying and committing all files for a frame, the controller sends:

```json
{"index":1,"sha256":"<64 lowercase hex characters>","accepted":true,"reason":"usable"}
```

POST `/oc/v1/jobs/<id>/verdict` uses the existing session header and private-LAN
guards. The index must be the next judged frame; its SHA-256 must match the
published original. `accepted` is a boolean and `reason` is an opaque 1–80
character string. An identical retry is idempotent. Conflicting, out-of-order
or new post-freeze verdicts fail. No verdict for an unpublished frame is valid.

When N accepted frames are judged, or all 2*N attempts are judged, freeze the
result immediately. Add this selection to the ordinary manifest:

```json
{"streaming":{"mode":"originals-v1","status":"complete","targetFrames":2,"attemptedFrames":3,"acceptedIndices":[1,3],"rejected":[{"index":2,"reason":"empty_signal"}]}}
```

The manifest's frames are the exact contiguous judged prefix, including
rejected attempts. Accepted/rejected indices are ascending, unique, disjoint
and partition that prefix. Complete means exactly N accepted; insufficient
means fewer than N after exactly 2*N judged attempts. An insufficient result
still completes transport to preserve evidence; it is not a valid measurement.
Unjudged lookahead cannot enter this manifest or alter its bytes after freeze.

Job state now reports complete and `resultSha256`; result GET and the normal
receipt ACK are available immediately. The PC compares the final selection
with its own immutable verdict ledger before completion/ACK. Unexpected
capture/format/file exceptions before freeze still fail and clean the job;
there is no generic failed-job archive API. After freeze, a failure of the
discarded lookahead does not invalidate the already-complete selected set.

## Writer lifetime

The selected result must not wait for an unwanted extra exposure. The controller
can change its scene after committing the frozen set. The phone drains/discards
the extra exposure separately and advertises `session.cameraReady:false` in
info/state until the capture writer has relinquished its files. Controllers
must wait for true before leasing the next capture, then respect that request's
settle/lock interval. A missing cameraReady field on an old phone uses the old
whole-job lifecycle.

ACK marks the result received but defers deletion until the writer has exited.
New jobs and lens changes remain blocked during that interval. Cancellation,
backgrounding, session end and expiry likewise stop/drain the writer before
cleanup. Temporary staging and used-ID replay protection keep the same scope
as the ordinary server. An idle verdict wait times out after 240 seconds;
controller heartbeats continue while files transfer.

Android and iOS compilation and synthetic selection/lifetime tests have passed.
Android emulator HTTP tests also exercise real generated DNG transfer, exact
1/6/18-frame targets, rejected replacements, 2*N exhaustion, replay and freeze
while the writer drains. See [verification](test-plan.md). Physical timing,
control stability and overlap on both phones still require matched experiments;
these software tests do not establish a performance improvement.
