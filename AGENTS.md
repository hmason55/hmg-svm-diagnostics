# Repository instructions

Use the GitHub plugin for GitHub operations. Do not open draft PRs.

This project is an observational Android API 19 diagnostic for Gen5W. The target is the US 2023 Hyundai IONIQ 5 Limited RWD with SVM. Keep firmware and vehicle compatibility unverified until observed on the target.

Run collectors under the app UID. Only add passive observers backed by documentation and parked noninterference testing. Keep camera opens, live buffers, vehicle commands, speed and gear overrides, factory hooks, service restarts, and privilege escalation out of the application. Treat device nodes, services, log events and frame evidence as separate observations. Store only filtered data in app-owned internal storage.

Keep the APK and NEMESIS package identity stable. Match the GitHub release tag, VERSION, APK version and manifest. Run Gradle checks and the Python package tests before submitting changes. Experimental publication requires explicit dispatch on main. Keep signing credentials outside the repository and APK.
