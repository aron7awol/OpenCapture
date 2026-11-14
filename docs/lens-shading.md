# Android RAW Lens Shading

OpenCapture `0.4.3` (Android build 10) requests the Camera2 lens shading
map where the selected camera advertises map reporting and FAST or HIGH_QUALITY
shading. Earlier 0.4.2/build 8 requested maps off. Install a
compatible controller/importer with this build; older consumers may reject its
DNG opcodes or averaging mode.

## Capture And Evidence

`rawShadingPolicy:camera2-dng-gain-map-v1` and boolean
`lensShadingMapRequested` describe the request policy in capabilities. The
app chooses advertised HIGH_QUALITY, then FAST, with map reporting ON. Merely
enabling reporting while shading is OFF can return an identity map.
Other processing requests and the original RAW_SENSOR samples are unchanged.
The separate legacy Uniformity Capture app retains its previous request policy.

Each original remains the exact DngCreator output. DngCreator embeds the actual
map in OpcodeList2 as four Bayer `GainMap` operations (opcode 9). The hashed
sidecar also saves `native.lensShadingMap`:

```json
{
  "rows": 2,
  "columns": 2,
  "channelOrder": ["R", "G_even", "G_odd", "B"],
  "gains": [1,1,1,1, 1,1,1,1, 1,1,1,1, 1,1,1,1]
}
```

The example is an identity map, not a device measurement. Gains are row-major,
four channels per grid point. Actual `shadingMode`, `lensShadingMapMode`, sensor
timestamps, CFA and array/crop metadata remain in the sidecar. Missing maps are
explicitly null. `rawLensShadingApplied` in capabilities and
`characteristics.lensShadingApplied` report true/false/unknown independently.
Camera2 defines the map as the remaining RAW correction even when the device
already applies some shading. That flag must not cause a second correction,
nor justify discarding a non-identity remaining map.

## Bounded Processing

New Android averages advertise **`bayer-mean-v3`**, not v1. The
[averaging contract](frame-averaging.md) records the exact embedded operations
as `gainMaps`. Four disjoint maps cover the active image with top/left parity
0 or 1, plane 0, one plane, row/column pitch 2, 1..64 grid points per axis,
origin zero and spacing `1/max(1,points-1)`. Finite gains must lie in [1,16].
Partial, overlapping, malformed or unknown radiometric programs fail explicitly.
An absent map remains an empty descriptor, never an invented flat field.

The DNG stage-2 image starts at ActiveArea; stored optical-black margins and the
preview crop are not gain-map offsets. Evaluate bilinear gains at normalized
DNG pixel centers `(x+0.5)/width`, `(y+0.5)/height`, extending grid edge values
where necessary. Use float64 interpolation rounded to float32, then multiply
the signed float32 black/white-normalized native site before 2x2 binning.
This follows the embedded DNG coordinates, which differ slightly from Camera2's
grid endpoint description. Original samples and native saturation are unchanged;
do not clamp corrected negative noise or values above one.

Phone averaging checks the actual Camera2 channels against the embedded maps.
Within one average, all contributing DNG normalization and gain maps must match;
a change fails instead of silently mixing processing. A compatible desktop
checks the reference DNG, sidecar maps and every retained DNG's normalization.
Keep all originals for a numerical parity test. Compact transfer cannot prove
the pixels of originals that were not transferred.

## Physical Verification

Software tests cover spatial/channel interpolation, odd active-area margins,
both TIFF byte orders, unchanged original decoding, signed noise, native
clipping, malformed metadata and Android/desktop moment parity. They do not
prove that a particular camera returns a useful or stable map under manual
exposure and white balance.

On the first physical camera, inspect a small original burst at fixed settings:
check actual ON + FAST/HIGH_QUALITY, map dimensions/channel ordering, embedded
DNG agreement and identity versus non-identity gains. Then request a short v3
average with `keepOriginals:true` and compare with those exact contributing
DNGs processed on the desktop. Record any map drift and stop on disagreement.
Factory metadata is not an independently measured flat field and does not prove
that remaining broad shading belongs to the photographed subject.

References: [Camera2 capture results](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#STATISTICS_LENS_SHADING_CORRECTION_MAP),
[DngCreator implementation](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/jni/android_hardware_camera2_DngCreator.cpp),
[DNG specification](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html).

This product includes DNG technology under license by Adobe.
