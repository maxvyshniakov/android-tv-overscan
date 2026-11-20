import hashlib
import io
import shlex
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import android_tv_overscan as app


class LauncherTests(unittest.TestCase):
    def test_pairing_code_is_sent_through_stdin(self):
        with (
            patch.object(app.getpass, "getpass", return_value="123456"),
            patch.object(app, "run", return_value="Successfully paired") as run,
            patch("sys.stdout", new=io.StringIO()),
        ):
            app.pair("adb", "192.168.1.2:37123", True)
        self.assertEqual(run.call_args.args[0], ["adb", "pair", "192.168.1.2:37123"])
        self.assertEqual(run.call_args.kwargs["input_text"], "123456\n")

    def test_wireless_guide_uses_distinct_pairing_and_connection_ports(self):
        with (
            patch.object(
                app, "ask", side_effect=["1", "192.168.1.2:37123", "192.168.1.2:40211"]
            ),
            patch.object(app, "pair") as pair,
            patch.object(app, "run", return_value="connected") as run,
            patch("sys.stdout", new=io.StringIO()),
        ):
            app.guide_connection("adb", True)
        pair.assert_called_once_with("adb", "192.168.1.2:37123", True)
        self.assertEqual(run.call_args.args[0], ["adb", "connect", "192.168.1.2:40211"])

    def test_unverified_apk_cannot_be_started_as_shell(self):
        commands = []

        def fake_run(command, **kwargs):
            commands.append(command)
            return {
                "getprop ro.build.version.sdk": "34",
                "pm has-feature android.software.leanback": "true",
                "am get-current-user": "0",
                "pm path --user 0 " + app.PACKAGE: "package:/data/app/test/base.apk",
                "sha256sum /data/app/test/base.apk": "0" * 64
                + "  /data/app/test/base.apk",
            }.get(command[-1], "Success")

        with (
            tempfile.TemporaryDirectory() as folder,
            patch.object(app, "run", side_effect=fake_run),
            patch("sys.stdout", new=io.StringIO()),
        ):
            apk = Path(folder) / "app.apk"
            apk.write_bytes(b"trusted package")
            with self.assertRaisesRegex(app.SetupError, "verification failed"):
                app.launch("adb", "streamer", apk)
        self.assertFalse(any("app_process" in command[-1] for command in commands))

    def test_device_states_and_model(self):
        found = app.parse_devices(
            "List of devices attached\nserial1 unauthorized usb:1\nserial2 device product:kirkwood model:Google_TV_Streamer\nserial3 offline\n"
        )
        self.assertEqual(
            found,
            [
                ("serial1", "unauthorized", "serial1"),
                ("serial2", "device", "Google TV Streamer"),
                ("serial3", "offline", "serial3"),
            ],
        )

    def test_selection_never_picks_an_unauthorized_device(self):
        with (
            patch.object(
                app, "run", return_value="serial1 unauthorized\nserial2 device model:TV"
            ),
            patch("sys.stdout", new=io.StringIO()),
        ):
            self.assertEqual(app.select_device("adb", None, False), "serial2")

    def test_ambiguous_selection_needs_explicit_target(self):
        with (
            patch.object(app, "run", return_value="one device\ntwo device"),
            patch("sys.stdout", new=io.StringIO()),
        ):
            with self.assertRaisesRegex(app.SetupError, "--serial"):
                app.select_device("adb", None, False)

    def test_shell_bootstrap_quotes_package_path(self):
        path = "/data/app/unusual ' $(touch no)/base.apk"
        command = app.helper_command(path, "0")
        tokens = shlex.split(command)
        self.assertEqual(tokens[0], "CLASSPATH=" + path)
        self.assertEqual(tokens[-1], "&")
        with self.assertRaises(app.SetupError):
            app.helper_command(path, "0; reboot")

    def test_endpoint_validation(self):
        self.assertEqual(app.endpoint("192.168.1.2:39123"), "192.168.1.2:39123")
        self.assertEqual(app.endpoint("[fe80::12]:5555"), "[fe80::12]:5555")
        for value in [
            "192.168.1.2",
            "--help",
            "host:99999",
            "host:0",
            "host:5555; reboot",
        ]:
            with self.assertRaises(app.SetupError):
                app.endpoint(value)

    def test_running_helper_is_reused_without_reinstall(self):
        commands = []

        def fake_run(command, **kwargs):
            commands.append(command)
            operation = command[-1]
            return {
                "getprop ro.build.version.sdk": "34",
                "pm has-feature android.software.leanback": "true",
                "am get-current-user": "0",
                "pm path --user 0 " + app.PACKAGE: "package:/data/app/test/base.apk",
                "sha256sum /data/app/test/base.apk": hashlib.sha256(
                    b"test apk"
                ).hexdigest()
                + "  /data/app/test/base.apk",
            }.get(operation, "Bundle[{ready=true, protocol=1}]")

        with (
            tempfile.TemporaryDirectory() as folder,
            patch.object(app, "run", side_effect=fake_run),
            patch("sys.stdout", new=io.StringIO()),
        ):
            apk = Path(folder) / "app.apk"
            apk.write_bytes(b"test apk")
            app.launch("adb", "streamer", apk)
        self.assertFalse(any("install" in command for command in commands))
        self.assertFalse(any("nohup" in command[-1] for command in commands))

    def test_bundle_values_are_parsed_from_content_call_output(self):
        output = (
            "Result: Bundle[{result=display control restored via port 37123,"
            " permission=true, ok=true, time=1795194245038, configured=true}]"
        )
        self.assertEqual(
            app.bundle_value(output, "result"),
            "display control restored via port 37123",
        )
        self.assertEqual(app.bundle_value(output, "time"), "1795194245038")
        self.assertEqual(app.bundle_value(output, "configured"), "true")
        self.assertEqual(app.bundle_value("Result: Bundle[{started=42}]", "started"), "42")
        self.assertEqual(app.bundle_value(output, "missing"), "")

    def test_app_key_pairing_uses_a_separate_adb_home_and_server(self):
        calls = []

        def fake_run(command, **kwargs):
            calls.append((command, kwargs))
            env = kwargs.get("env") or {}
            if command[-1] == "start-server":
                key = Path(env["HOME"]) / ".android" / "adbkey"
                key.parent.mkdir()
                key.write_text("-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----\n")
            if command[-2] == "pair":
                return "Successfully paired to 192.168.1.2:37123"
            return ""

        with (
            patch.object(app, "run", side_effect=fake_run),
            patch.object(app, "ask", return_value="192.168.1.2:37123"),
            patch.object(app.getpass, "getpass", return_value="123456"),
            patch("sys.stdout", new=io.StringIO()),
        ):
            pem = app.pair_app_key("adb", True)
        self.assertIn(b"BEGIN PRIVATE KEY", pem)
        homes = {kwargs["env"]["HOME"] for _, kwargs in calls}
        self.assertEqual(len(homes), 1)
        home = homes.pop()
        self.assertNotEqual(home, str(Path.home()))
        self.assertFalse(Path(home).exists(), "temporary adb home is deleted")
        for command, _ in calls:
            self.assertEqual(command[:2], ["adb", "-P"])
            self.assertNotEqual(command[2], "5037")
        pair = next(kwargs for command, kwargs in calls if "pair" in command)
        self.assertEqual(pair["input_text"], "123456\n")
        self.assertEqual(calls[-1][0][-1], "kill-server")

    def test_app_key_is_streamed_through_stdin_not_arguments(self):
        commands = []
        pem = b"-----BEGIN PRIVATE KEY-----\nSECRET\n-----END PRIVATE KEY-----\n"

        def fake_run(command, **kwargs):
            commands.append((command, kwargs))
            operation = command[-1]
            if "autoRestoreStatus" in operation:
                if any("autoRestoreNow" in c[-1] for c, _ in commands):
                    return "Bundle[{ok=true, time=20, result=restored, configured=true}]"
                return "Bundle[{configured=false, result=never run}]"
            if "autoRestoreNow" in operation:
                return "Bundle[{started=10}]"
            return ""

        with (
            patch.object(app, "run", side_effect=fake_run),
            patch.object(app, "pair_app_key", return_value=pem),
            patch.object(app.time, "sleep"),
            patch("sys.stdout", new=io.StringIO()),
        ):
            self.assertTrue(app.setup_auto_restore("adb", "tv", "0", True, force=True))
        for command, kwargs in commands:
            self.assertNotIn("SECRET", " ".join(command))
        write = next(kw for c, kw in commands if c[-1].startswith("content write"))
        self.assertEqual(write["input_text"], pem.decode("ascii"))
        self.assertTrue(
            any("WRITE_SECURE_SETTINGS" in c[-1] and "pm grant" in c[-1] for c, _ in commands)
        )

    def test_auto_restore_is_not_offered_without_a_terminal(self):
        with patch.object(
            app, "run", return_value="Bundle[{configured=false, result=never run}]"
        ) as run:
            self.assertFalse(app.setup_auto_restore("adb", "tv", "0", False))
        self.assertEqual(run.call_count, 1)

    def test_restore_now_waits_for_a_result_newer_than_the_request(self):
        replies = iter(
            [
                "Bundle[{started=100}]",
                "Bundle[{ok=true, time=50, result=old, configured=true}]",
                "Bundle[{ok=false, time=150, result=helper did not attach, configured=true}]",
            ]
        )
        with (
            patch.object(app, "run", side_effect=lambda *a, **k: next(replies)),
            patch.object(app.time, "sleep"),
        ):
            ok, message = app.restore_now(lambda c, **k: app.run(c, **k), "0")
        self.assertFalse(ok)
        self.assertEqual(message, "helper did not attach")

    def test_unsupported_android_stops_before_installing(self):
        with patch.object(app, "run", return_value="33") as run:
            with self.assertRaisesRegex(app.SetupError, "Android TV 14"):
                app.launch("adb", "streamer", Path("app.apk"))
            self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
