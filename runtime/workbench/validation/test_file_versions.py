"""验证编辑器在平台读取文件期间保存了新版本。"""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "development"))
import tool


class FileVersionTest(unittest.TestCase):
    def test_eof_page_rechecks_version_after_editor_save(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "notes.txt"
            path.write_bytes(b"first\nsecond\n")
            fingerprint = tool.version

            def save_after_first_hash(target):
                digest = fingerprint(target)
                if target.read_bytes() == b"first\nsecond\n":
                    target.write_bytes(b"changed\n")
                return digest

            with patch.object(tool, "version", side_effect=save_after_first_hash):
                with self.assertRaisesRegex(ValueError, "已改变"):
                    tool.read(path, {"start": 4})
            self.assertEqual(b"changed\n", path.read_bytes())


if __name__ == "__main__":
    unittest.main()
