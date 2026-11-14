# Third-party notices

This product includes DNG technology under license by Adobe.

OpenCapture Android is GPL-3.0-only. Its source license is in the repository
root. Kotlin standard library and AndroidX Core are Apache-2.0 components;
their original copyright/license notices remain in their distributed artifacts.

NanoHTTPD 2.3.1 is a BSD-3-Clause runtime dependency used for the foreground
camera HTTP server. Its full notice is bundled in
`app/src/main/res/raw/third_party_notices.txt` and available in About > Licenses.

Build/test dependencies include Gradle (Apache-2.0), Android Gradle Plugin
(Apache-2.0), Kotlin Gradle Plugin (Apache-2.0) and JUnit 4 (EPL-1.0). These
tools/tests are not part of the application's capture protocol. The Gradle
wrapper is distributed in its original form with its license notices and a
pinned distribution checksum. Android platform APIs are supplied by Android.

Dependency versions are explicit in the Gradle files. No private renderer,
display correction solver, calibration assets, signing keys or credentials
are included in this source export.
