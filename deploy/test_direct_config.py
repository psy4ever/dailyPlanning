import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prepare_direct", Path(__file__).with_name("prepare-direct.py"))
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


class DirectConfigTest(unittest.TestCase):
    def test_preserves_credentials_and_replaces_host_without_duplicates(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / ".env"
            target.write_text("MYSQL_PASSWORD=keep-it\nDAILY_PASSWORD=keep-this-too\nAPI_PUBLIC_HOST=8.8.8.8\n", encoding="utf-8")
            config.configure(target, "43.142.121.249")
            config.configure(target, "43.142.121.249")
            result = target.read_text(encoding="utf-8")
            self.assertIn("MYSQL_PASSWORD=keep-it\n", result)
            self.assertIn("DAILY_PASSWORD=keep-this-too\n", result)
            self.assertEqual(1, result.count("API_PUBLIC_HOST="))
            self.assertIn("API_PUBLIC_HOST=43.142.121.249\n", result)

    def test_rejects_nonpublic_or_invalid_addresses_without_touching_file(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / ".env"
            original = "MYSQL_PASSWORD=unchanged\n"
            target.write_text(original, encoding="utf-8")
            for host in ("127.0.0.1", "10.0.0.1", "192.168.1.10", "bad; command", "43.142.121.249\nOTHER=1"):
                with self.assertRaises(ValueError):
                    config.configure(target, host)
                self.assertEqual(original, target.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
