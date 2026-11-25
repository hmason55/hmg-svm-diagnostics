# HMG SVM Diagnostics

`gen5w-svm-diagnostics` is an experimental Android 4.4 (API 19) diagnostic for the US 2023 Hyundai IONIQ 5 Limited RWD with Gen5W and Surround View Monitor. It records system metadata and camera-related log hints to help correlate factory state changes with an independent driving timeline. Exact firmware compatibility, wireless reachability on Gen5W and factory-camera noninterference remain unverified.

Configure, start, stop and export sessions while parked. By default a foreground Android service samples every 2 seconds for up to 15 minutes, with a 50 MB session limit. The app scans under its ordinary app UID and collection works offline. Completed sessions can be downloaded through a short-lived URL on a selected local network address.

Device visibility and Binder registration provide interface evidence. On this build SVM frame delivery, physical camera activity and measured vehicle speed all remain unknown.

For debugging photos, tap **Photo View** and choose **Current feedback** or a saved session. The screen stays awake and shows large, numbered pages with the build and session ID in the header. Use **Next** to photograph each page while parked.

## Build and verify

Use JDK 17, Gradle 8.10.2, Python 3.12 and Android SDK Platform 35 with Build Tools 35.0.0. Set `ANDROID_HOME` and accept the Android SDK licenses.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python -m unittest discover -s tests -v
python tools/build_package.py app/build/outputs/apk/debug/app-debug.apk
```

The builder verifies APK identity, target/minimum API 19, absence of native code, the KitKat-compatible v1 signature and the direct NEMESIS archive layout. It writes `dist/gen5w-svm-diagnostics-0.1.0.zip` and `dist/SHA256SUMS`. Local debug and CI artifacts use development signing. Use the documented publish workflow to build an experimental prerelease with a persistent release certificate.

- [Architecture and session schema](docs/ARCHITECTURE.md)
- [Upstream research and interface inventory](docs/INTERFACES.md)
- [Cloud publishing](docs/PUBLISHING.md)
- [Parked and moving-vehicle test guide](docs/VEHICLE-TEST.md)
- [Known limitations](docs/LIMITATIONS.md)

License: [AGPL-3.0-only](LICENSE).
