# Publish an experimental release

Use a public GitHub source repository with the Cloud Registry GitHub App installed for that repository. The Android package remains `gen5w.svm.diagnostics` even if the GitHub repository name differs.

1. Configure repository Actions secrets `SVM_KEYSTORE_BASE64`, `SVM_STORE_PASSWORD`, `SVM_KEY_ALIAS` and `SVM_KEY_PASSWORD`. The base64 value is a persistent signing keystore. Keep a backup outside the repository so updates can use the same certificate.
2. Verify CI on `main`. Dispatch **Publish experimental SVM diagnostics** from `main` with `0.1.0`, matching `VERSION`. The workflow tests, signs, validates and stages the exact ZIP and `SHA256SUMS` before publishing the GitHub prerelease. It rejects any existing published version or version tag pointing to a different commit.
3. Sign in to [Cloud Registry Publish](https://hmason55.github.io/nemesis-package-manager/#publish), connect the repository, and register its exact owner/name. Select the published GitHub release and the `gen5w-svm-diagnostics-0.1.0.zip` asset from the release.
4. Declare ID `gen5w.svm.diagnostics`, version `0.1.0` and name **SVM Diagnostics (experimental)**. Target the US 2023 Hyundai IONIQ 5 Limited RWD, Gen5W, with SVM. Record exact firmware as unknown until verified and leave support unverified. Firmware `260507` is a Package Manager test result.
5. Acknowledge redistribution permissions under AGPL-3.0 and submit the exact asset for trusted validation. Confirm it reaches **Published**. If the interface omits prereleases or the trusted validator rejects the submission, record the actual behavior before changing release policy or artifact bytes.
6. While parked, open Cloud Registry in the head-unit Package Manager, verify the package ID and SHA-256, and confirm a normal NEMESIS installation without USB. Use the [vehicle test procedure](VEHICLE-TEST.md).

Published versions are immutable. Changes require a new version in `VERSION` with a new tag and artifact. The [Registry guide](https://github.com/hmason55/nemesis-package-manager/blob/main/docs/REGISTRY-GUIDE.md) describes the current publisher interface.
