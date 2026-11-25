# Architecture

The Android app has a dashboard Activity and a private foreground Service. A parked user starts a scan or collection. The Service uses `START_NOT_STICKY`, creates no boot receivers and runs under the APK UID. Closing the Activity keeps an explicitly started session running. Android stopping or killing the service ends collection.

System and device discovery run once per scan or session. System metadata, interface discovery, Binder discovery and filtered log observation can be disabled independently. The sample loop checks visibility for discovered video paths, available space, and at most every 10 seconds checks the registration of `media.camera` if it was initially discovered. A single child process reads filtered logcat messages into a bounded queue. Only the tag, time and fixed categorical result pass into session files. The dashboard uses cached inventory.

## Session storage

Sessions live under Android app-owned `files/sessions/session-<wall-ms>-<random>/`.

| Member | Content |
| --- | --- |
| `metadata.json` | Schema version, session ID, clock anchors, enabled collectors and limits |
| `inventory.json` | Allowlisted properties, hardware/driver metadata, device paths, Binder names and capability issues |
| `samples.jsonl` | Incremental metadata observations with wall and monotonic elapsed timestamps |
| `camera-events.jsonl` | Tag and category for retained events. Includes log timestamp and observation time |
| `errors.jsonl` | Safe status, collector and evidence source, with no command stderr |
| `summary.txt` | Human-readable report with seven separately reported pipeline stages |
| `summary.json` | Structured report and completion marker |

Each observation includes the session ID, wall-clock time in Unix milliseconds, elapsed milliseconds from `SystemClock.elapsedRealtime()`, speed source and nullable speed in km/h, collector, observation type, source, value, confidence, evidence and status. Speed sources are `unknown` in the app and should be marked `external` on an independent timeline. A future app-measured reading would use `measured`. Use the wall start timestamp to align the clocks and elapsed time to handle wall-clock adjustments.

Metadata, inventory and summaries are committed by same-directory rename after a synced write. JSON Lines append incrementally. Reopening interrupted sessions ignores a malformed last line. The app displays recoverable sample counts for interrupted sessions. The ZIP exporter accepts completed sessions. Uninstalling removes app-owned internal data through Android.

The sampler stops on explicit cancellation, 15 minutes elapsed, the session budget, or low space. It reserves room for finalization and stops at a free-space floor of 8 MiB. Worker failures leave partial files for inspection. The bounded queue can drop event hints if the event rate exceeds what the sampler can retain. The report counts drops at the queue limit.

## Wireless export

Select a completed session and a local IPv4 address. The app builds a temporary archive, binds a listener to that address and displays a URL with a random 128-bit token. The listener serves one fixed archive to exact matching HTTP GET requests. It never derives a filesystem path from a request. It shuts down when the export screen is closed, the app leaves the foreground, or five minutes elapse, and closes any active socket. The file is removed from the cache at the end of the export task.

HTTP is cleartext. Use a trusted network or private hotspot and treat the URL as a temporary bearer credential. Hotspot client isolation and OEM network restrictions can prevent another device from connecting.
