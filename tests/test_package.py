import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from tools.build_package import APK_MEMBER, check_archive, check_manifest, manifest


class PackageTest(unittest.TestCase):
    def test_manifest_and_version(self):
        check_manifest(manifest("0.1.0"), "0.1.0")
        for invalid in ("0.2.0", "0.1.0-build.1", "0.01.0"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                check_manifest(manifest("0.1.0"), invalid)

    def test_usb_descriptor_and_hooks_are_rejected(self):
        for line in ("type=install", "preinstall=bin/start.sh"):
            broken = manifest("0.1.0").replace("[nemesis]", "[nemesis]\n" + line)
            with self.subTest(line=line), self.assertRaises(ValueError):
                check_manifest(broken, "0.1.0")

    def test_layout_allows_only_the_direct_install_members(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "package.zip"
            with ZipFile(path, "w") as archive:
                archive.writestr("NEMESIS.data", manifest("0.1.0"))
                archive.writestr(APK_MEMBER, "layout fixture")
            check_archive(path, "0.1.0")
            with ZipFile(path, "a") as archive:
                archive.writestr("../escaped", "")
            with self.assertRaises(ValueError):
                check_archive(path, "0.1.0")
