"""Verify installer cleanup without root or host installation; uses the real built archive."""
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def find_archives():
    """Canonical artifacts land in packaging/dist/; legacy packaging/*.deb still accepted."""
    return sorted((ROOT / "packaging/dist").glob("linux-device-manager_*.deb")) + \
        sorted((ROOT / "packaging").glob("linux-device-manager_*.deb"))


class VerifyDebTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ldm-verifier-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        packaging = self.root / "packaging"
        (packaging / "dist").mkdir(parents=True)
        archives = find_archives()
        self.assertTrue(archives, "build the .deb before running verifier tests")
        (packaging / "dist" / archives[0].name).symlink_to(archives[0])
        shutil.copy2(ROOT / "packaging/verify-deb.sh", packaging / "verify-deb.sh")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.script("id", 'echo 0')
        self.script("dpkg-query", 'if [ "$TEST_EXISTING" = 1 ]; then echo installed; else exit 1; fi')
        self.script("dpkg", '''
printf '%s\n' "$*" >> "$TEST_CALLS"
case "$1" in
    -i) exit "$TEST_INSTALL_EXIT" ;;
    -r) exit "$TEST_REMOVE_EXIT" ;;
esac
exit 99
''')
        self.script("timeout", '''
echo "fixture app smoke output"
if [ "$TEST_SIGNAL" = 1 ]; then kill -TERM "$PPID"; fi
exit "$TEST_APP_EXIT"
''')
        self.script("mktemp", ': > "$TEST_SMOKE"\nprintf "%s\\n" "$TEST_SMOKE"')
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        DPKG_INSTALL="1", TEST_EXISTING="0", TEST_INSTALL_EXIT="0",
                        TEST_REMOVE_EXIT="0", TEST_APP_EXIT="124", TEST_SIGNAL="0",
                        TEST_CALLS=str(self.root / "calls"), TEST_SMOKE=str(self.root / "smoke.log"))

    def script(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\nset -eu\n" + body + "\n")
        path.chmod(0o755)

    def run_verifier(self, *args, **env):
        result = subprocess.run(["sh", str(self.root / "packaging/verify-deb.sh"), *args],
                                env=dict(self.env, **env), text=True, capture_output=True, timeout=30)
        calls = self.root / "calls"
        return result, calls.read_text().splitlines() if calls.exists() else []

    def assert_cleanup(self, calls):
        self.assertEqual(2, len(calls), calls)
        self.assertTrue(calls[0].startswith("-i "), calls)
        self.assertEqual("-r linux-device-manager", calls[1])
        self.assertFalse((self.root / "smoke.log").exists())

    def test_timeout_is_success_and_always_uninstalls(self):
        result, calls = self.run_verifier()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("ran until timeout", result.stdout)
        self.assert_cleanup(calls)

    def test_early_exit_and_crash_are_failures_but_still_uninstall(self):
        for code in ("0", "1", "139"):
            with self.subTest(code=code):
                (self.root / "calls").unlink(missing_ok=True)
                result, calls = self.run_verifier(TEST_APP_EXIT=code)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn("unexpected exit code " + code, result.stdout)
                self.assert_cleanup(calls)

    def test_failed_install_is_cleaned_up(self):
        result, calls = self.run_verifier(TEST_INSTALL_EXIT="17")
        self.assertEqual(17, result.returncode, result.stdout + result.stderr)
        self.assert_cleanup(calls)

    def test_signal_still_uninstalls(self):
        result, calls = self.run_verifier(TEST_SIGNAL="1", TEST_APP_EXIT="143")
        self.assertEqual(143, result.returncode, result.stdout + result.stderr)
        self.assert_cleanup(calls)

    def test_failed_cleanup_fails_verification(self):
        result, calls = self.run_verifier(TEST_REMOVE_EXIT="1")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assert_cleanup(calls)

    def test_existing_installation_is_not_replaced_or_removed(self):
        result, calls = self.run_verifier(TEST_EXISTING="1")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("disposable environment", result.stdout)
        self.assertEqual([], calls)

    def test_multiple_archives_require_an_explicit_selection(self):
        first = next((self.root / "packaging/dist").glob("*.deb"))
        (first.parent / "linux-device-manager_9.9.9_amd64.deb").symlink_to(first.resolve())
        result, calls = self.run_verifier()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)
        result, calls = self.run_verifier(str(first), DPKG_INSTALL="0")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], calls)

    def test_missing_native_version_floor_is_rejected_before_install(self):
        real_dpkg_deb = shlex.quote(shutil.which("dpkg-deb"))
        self.script("dpkg-deb", f'''
if [ "$1" = -f ] && [ "$3" = Depends ]; then
    {real_dpkg_deb} "$@" | sed 's/libgtk-4-1 (>= 4.14.5)/libgtk-4-1/'
else
    exec {real_dpkg_deb} "$@"
fi
''')
        result, calls = self.run_verifier()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("missing tested GTK", result.stderr)
        self.assertEqual([], calls)


class ArchiveIntegrityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="ldm-archive-integrity-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.baseline = cls.root / "baseline"
        archives = find_archives()
        if not archives:
            raise AssertionError("build the .deb before running verifier tests")
        subprocess.run(["dpkg-deb", "-R", str(archives[0]), str(cls.baseline)], check=True)

    def verify_mutation(self, mutation):
        with tempfile.TemporaryDirectory(dir=self.root) as temp:
            temp = Path(temp)
            stage = temp / "stage"
            shutil.copytree(self.baseline, stage, symlinks=True)
            mutation(stage)
            deb = temp / "mutated.deb"
            subprocess.run(["dpkg-deb", "-Znone", "--build", "--root-owner-group", str(stage), str(deb)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            result = subprocess.run(["sh", str(ROOT / "packaging/verify-deb.sh"), str(deb)],
                                    env=dict(os.environ, DPKG_INSTALL="0"), capture_output=True, text=True, timeout=30)
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("FAIL:", result.stderr)

    def test_each_invalid_control_field_is_rejected(self):
        for old, new in [("Package: linux-device-manager", "Package: broken-manager"),
                         ("Architecture: amd64", "Architecture: arm64"),
                         ("libgtk-4-1 (>= 4.14.5)", "libgtk-4-1"),
                         (", policykit-1", ""), (", udev", "")]:
            with self.subTest(field=old):
                def mutate(stage):
                    control = stage / "DEBIAN/control"
                    control.write_text(control.read_text().replace(old, new))
                self.verify_mutation(mutate)

    def test_each_required_executable_permission_is_enforced(self):
        for name in ["usr/bin/linux-device-manager", "usr/libexec/ldm-helper",
                     "opt/linux-device-manager/runtime/bin/java"]:
            with self.subTest(file=name):
                self.verify_mutation(lambda stage: (stage / name).chmod(0o644))

    def test_each_application_jar_is_required(self):
        for artifact in ["ldm-core", "ldm-gui-gtk", "gtk", "glib"]:
            with self.subTest(artifact=artifact):
                def mutate(stage):
                    next((stage / "opt/linux-device-manager/lib").glob(artifact + "-*.jar")).unlink()
                self.verify_mutation(mutate)

    def test_missing_scripts_and_policy_are_rejected(self):
        for name in ["DEBIAN/postinst", "DEBIAN/postrm", "usr/share/polkit-1/actions/org.ldm.policy"]:
            with self.subTest(file=name):
                self.verify_mutation(lambda stage: (stage / name).unlink())

    def test_duplicate_core_jar_is_rejected(self):
        for mode in (0o644, 0o755):
            with self.subTest(mode=mode):
                def mutate(stage):
                    lib = stage / "opt/linux-device-manager/lib"
                    duplicate = lib / "ldm-core-old.jar"
                    shutil.copy2(next(lib.glob("ldm-core-*.jar")), duplicate)
                    duplicate.chmod(mode)
                self.verify_mutation(mutate)


if __name__ == "__main__":
    unittest.main(verbosity=2)
