import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class RenderingInstallerTest(unittest.TestCase):
    def run_installer(self, installed, versions=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "apt.log"
            commands = {
                "sudo": '#!/bin/bash\nexec "$@"\n',
                "dpkg-query": "#!/bin/bash\ncat <<'PACKAGES'\n" + installed + "\nPACKAGES\n",
                "apt-get": '#!/bin/bash\nprintf "%s\\n" "$*" >> "$APT_LOG"\n',
            }
            for name, content in commands.items():
                command = root / name
                command.write_text(content)
                command.chmod(0o755)
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", APT_LOG=str(log))
            env.pop("MAPI_MESA_VERSION", None)
            env.pop("MAPI_XVFB_VERSION", None)
            if versions:
                env.update(MAPI_MESA_VERSION="25.2.8-0ubuntu0.24.04.2", MAPI_XVFB_VERSION="2:21.1.12-1ubuntu1.8")
            result = subprocess.run(
                ["bash", str(Path(__file__).with_name("ci-install-rendering.sh"))],
                env=env, capture_output=True, text=True,
            )
            return result, log.read_text().splitlines() if log.exists() else []

    def test_newer_runner_libraries_are_pinned_with_the_drivers(self):
        result, calls = self.run_installer(
            "libgbm1:amd64\tmesa\tinstalled\n"
            "libegl-mesa0:amd64\tmesa\tinstalled\n"
            "libglx-mesa0:amd64\tmesa\tinstalled\n"
            "mesa-va-drivers:amd64\tmesa\tinstalled\n"
            "mesa-vdpau-drivers:amd64\tmesa\tconfig-files\n"
            "mesa-utils\tmesa-demos\tinstalled"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[0], "update")
        install = calls[1].split()
        self.assertIn("--allow-downgrades", install)
        self.assertIn("--yes", install)
        for package in ["libgbm1:amd64", "libegl-mesa0:amd64", "libglx-mesa0:amd64", "mesa-va-drivers:amd64", "libgl1-mesa-dri", "mesa-libgallium", "mesa-vulkan-drivers"]:
            self.assertIn(f"{package}=25.2.8-0ubuntu0.24.04.2", install)
        self.assertFalse(any(item.startswith(("mesa-utils=", "mesa-vdpau-drivers")) for item in install))
        self.assertIn("xvfb=2:21.1.12-1ubuntu1.8", install)

    def test_missing_version_fails_before_apt_changes(self):
        result, calls = self.run_installer("", versions=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
