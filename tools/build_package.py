"""Build and inspect the direct NEMESIS installer for an Android API 19 APK."""

import argparse
import configparser
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_ID = "gen5w.svm.diagnostics"
APK_MEMBER = "app/SvmDiagnostics.apk"
COMPONENT = PACKAGE_ID + "/.MainActivity"
VERSION_PATTERN = r"(?:0|[1-9]\d{0,2})(?:\.(?:0|[1-9]\d{0,2})){2}"


def artifact_version():
    version = (ROOT / "VERSION").read_text(encoding="ascii").strip()
    if not re.fullmatch(VERSION_PATTERN, version) or version == "0.0.0":
        raise ValueError("VERSION must be a nonzero major.minor.patch with 0–999 per part")
    return version


def manifest(version):
    return (ROOT / "packaging/NEMESIS.data.in").read_text(encoding="utf-8").replace(
        "@VERSION@", version
    )


def check_manifest(text, version):
    cfg = configparser.ConfigParser(interpolation=None)
    cfg.read_string(text)
    if set(cfg.sections()) != {"nemesis", "app.0"} or cfg.defaults():
        raise ValueError("Expected a manifest with a single Android app and no hooks")
    package = cfg["nemesis"]
    app = cfg["app.0"]
    if (
        set(package) != {"version", "id", "name", "package-version", "short-desc", "detailed-desc"}
        or package.get("version") != "1.1"
        or package.get("id") != PACKAGE_ID
        or package.get("package-version") != version
        or not re.fullmatch(VERSION_PATTERN, version)
        or len(package.get("short-desc", "")) > 64
        or len(package.get("detailed-desc", "")) > 256
    ):
        raise ValueError("Invalid package descriptor, identity, version or metadata")
    if dict(app) != {
        "kind": "apk", "path": APK_MEMBER, "package": PACKAGE_ID, "component": COMPONENT
    }:
        raise ValueError("Invalid Android app declaration")


def run_sdk(tool, *arguments):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME to an Android SDK with Build Tools 35.0.0")
    root = Path(sdk) / "build-tools" / "35.0.0"
    suffix = {"aapt": ".exe", "apksigner": ".bat"}[tool] if os.name == "nt" else ""
    return subprocess.run(
        [str(root / (tool + suffix)), *map(str, arguments)],
        capture_output=True, text=True, check=True,
    ).stdout


def verify_apk(apk, version, release):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if not {"AndroidManifest.xml", "classes.dex"} <= set(names):
            raise ValueError("Missing binary manifest or DEX")
        if any(name.startswith("lib/") or name.endswith(".so") for name in names):
            raise ValueError("The API 19 installer must be ABI-neutral (no native libraries)")
        if archive.testzip() is not None:
            raise ValueError("APK CRC check failed")
    metadata = run_sdk("aapt", "dump", "badging", apk)
    version_code = sum(int(part) * 1000 ** (2 - index)
                       for index, part in enumerate(version.split(".")))
    expected = [
        f"package: name='{PACKAGE_ID}' versionCode='{version_code}' versionName='{version}'",
        "sdkVersion:'19'",
        "targetSdkVersion:'19'",
        f"launchable-activity: name='{PACKAGE_ID}.MainActivity'",
    ]
    if any(line not in metadata for line in expected) or (release and "application-debuggable" in metadata):
        raise ValueError("APK identity, API level, activity or release build type mismatch")
    signature = run_sdk("apksigner", "verify", "--min-sdk-version", "19", "--verbose", apk)
    if "Verified using v1 scheme (JAR signing): true" not in signature:
        raise ValueError("KitKat requires an API 19-compatible v1 APK signature")


def check_archive(path, version):
    with zipfile.ZipFile(path) as archive:
        if sorted(archive.namelist()) != sorted(["NEMESIS.data", APK_MEMBER]):
            raise ValueError("Unexpected, duplicate or missing installer member")
        if archive.testzip() is not None:
            raise ValueError("Installer CRC check failed")
        check_manifest(archive.read("NEMESIS.data").decode("utf-8"), version)


def build(apk, output, release=False):
    version = artifact_version()
    verify_apk(apk, version, release)
    output.mkdir(parents=True, exist_ok=True)
    name = f"gen5w-svm-diagnostics-{version}.zip"
    with tempfile.TemporaryDirectory(dir=output) as staging:
        stage = Path(staging)
        archive_path = stage / name
        with zipfile.ZipFile(archive_path, "w") as archive:
            descriptor = zipfile.ZipInfo("NEMESIS.data")
            descriptor.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(descriptor, manifest(version))
            application = zipfile.ZipInfo(APK_MEMBER)
            application.compress_type = zipfile.ZIP_DEFLATED
            with apk.open("rb") as source, archive.open(application, "w") as destination:
                shutil.copyfileobj(source, destination)
        check_archive(archive_path, version)
        with archive_path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        sums = stage / "SHA256SUMS"
        sums.write_text(f"{digest}  {name}\n", encoding="ascii", newline="\n")
        archive_path.replace(output / name)
        sums.replace(output / sums.name)
    return output / name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "dist")
    parser.add_argument("--release", action="store_true")
    args = parser.parse_args()
    try:
        print(build(args.apk.resolve(), args.out, args.release))
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Installer validation failed: {getattr(error, 'stderr', None) or error}\n")


if __name__ == "__main__":
    main()
