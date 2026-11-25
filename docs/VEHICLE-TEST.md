# Vehicle validation

Target a US 2023 Hyundai IONIQ 5 Limited RWD equipped with SVM and an Android Gen5W unit. Record exact General Settings → SW Info, Android ABI, NEMESIS version, Package Manager version and installer SHA-256. Keep this identification with the test report. All app interaction is performed while parked.

## Parked

1. Install the signed experimental package using Cloud Registry with no USB connected. Launch from Package Manager.
2. Establish a baseline for factory reverse and SVM views with the diagnostic app idle. Run **Scan System**, review inventory and verify that stock views still open and close normally. Repeat while a short diagnostic session runs with the dashboard in the background.
3. Stop collection while parked. Open **View Summary** and verify timestamps, sample count, explicit capability errors and separate pipeline evidence states. Check that stopped collection does not grow the session.
4. Join a trusted Wi-Fi network or shared hotspot. Select **Export Session**, choose the matching interface and download the archive on the second device. Inspect its contents, verify that closing the export dialog stops the download endpoint, and repeat to verify the five-minute limit.
5. Reopen the app after a shutdown or interruption. Confirm that collection stays stopped and existing sessions are readable. Record any missing capabilities. If stock camera behavior changes, stop this test before proceeding.

## Moving observation

After the parked workflow and noninterference checks pass, start a fresh bounded session while parked. A passenger or independent recording system can annotate clock time, externally measured speed and factory UI state. Drive normally in a controlled environment. Let the factory view close at its normal cutoff, record any ordinary rear-view driving and BVM behavior, then park before touching the diagnostic controls.

Use time-synchronized annotations such as:

| Wall time (include time zone) | Speed km/h | Speed source | Factory observation |
| --- | ---: | --- | --- |
| Add observation time | Add measured speed if available | external or unknown | parked baseline, normal SVM closure, ordinary rear view or BVM |

Join the timeline to `wallStartMs` and `elapsedMs` in the exported archive. Treat the approximately 6 mph cutoff as an observation to verify. Record whether metadata, Binder registration or log hints changed. With no validated SVM frame counter, leave frame continuity and individual camera activity unknown.
