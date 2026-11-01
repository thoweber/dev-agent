#!/usr/bin/env python3
"""
Unit tests for scripts/verify_markdown_links.py.
"""

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

# Add repository root to python sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "scripts"))
import verify_markdown_links


class TestVerifyMarkdownLinks(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_valid_relative_links_pass(self):
        # Create target files
        target = self.root / "docs" / "target.md"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("# Target\n", encoding="utf-8")

        source = self.root / "README.md"
        source.write_text("See [Target Document](docs/target.md) and [Target with anchor](docs/target.md#overview).\n", encoding="utf-8")

        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(errors, [])

    def test_external_and_anchor_links_pass(self):
        source = self.root / "links.md"
        source.write_text(
            "Visit [GitHub](https://github.com/thoweber/dev-agent), [Docs](http://example.com), "
            "[Email](mailto:dev@example.com), and [Section](#intro).\n",
            encoding="utf-8",
        )
        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(errors, [])

    def test_prohibits_file_uri_scheme(self):
        source = self.root / "bad_uri.md"
        source.write_text("See [File Link](file:///home/developer/projects/dev-agent/README.md).\n", encoding="utf-8")

        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(len(errors), 1)
        line_no, link, msg = errors[0]
        self.assertEqual(line_no, 1)
        self.assertIn("file://", link)
        self.assertIn("Absolute 'file://' URI scheme is strictly prohibited", msg)

    def test_prohibits_absolute_filesystem_paths(self):
        source = self.root / "absolute_path.md"
        source.write_text("See [Root Link](/home/user/project/doc.md).\n", encoding="utf-8")

        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(len(errors), 1)
        line_no, link, msg = errors[0]
        self.assertEqual(line_no, 1)
        self.assertIn("/home/user/project/doc.md", link)
        self.assertIn("Absolute filesystem path is prohibited", msg)

    def test_flags_non_existent_relative_target(self):
        source = self.root / "missing.md"
        source.write_text("Broken [Missing File](does_not_exist.md).\n", encoding="utf-8")

        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(len(errors), 1)
        line_no, link, msg = errors[0]
        self.assertEqual(line_no, 1)
        self.assertEqual(link, "does_not_exist.md")
        self.assertIn("non-existent path", msg)

    def test_ignores_links_in_fenced_code_blocks(self):
        source = self.root / "code_block.md"
        source.write_text(
            """# Title
```markdown
[File Link](file:///home/dev/file.md)
[Broken Link](does_not_exist.md)
```
""",
            encoding="utf-8",
        )
        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(errors, [])

    def test_ignores_links_in_inline_code_spans(self):
        source = self.root / "inline_code.md"
        source.write_text(
            "Use `[Example](file:///absolute/path.md)` as syntax or `[Missing](missing.md)`.\n",
            encoding="utf-8",
        )
        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(errors, [])

    def test_find_markdown_files_excludes_ignored_directories(self):
        (self.root / "valid.md").write_text("# Valid\n", encoding="utf-8")
        (self.root / ".git" / "COMMIT_EDITMSG.md").parent.mkdir(parents=True, exist_ok=True)
        (self.root / ".git" / "COMMIT_EDITMSG.md").write_text("# Git\n", encoding="utf-8")
        (self.root / "target" / "generated.md").parent.mkdir(parents=True, exist_ok=True)
        (self.root / "target" / "generated.md").write_text("# Target\n", encoding="utf-8")

        found = verify_markdown_links.find_markdown_files(self.root)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0], self.root / "valid.md")

    def test_code_block_fence_matching_preserves_code_within_nested_fences(self):
        source = self.root / "nested_fences.md"
        # 4 backticks opener should not be closed by 3 backticks
        source.write_text(
            """````carousel
[Slide Link](file:///home/bad/slide1.md)
```python
print("nested code")
```
````
""",
            encoding="utf-8",
        )
        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(errors, [])

    def test_flags_links_escaping_repo_root(self):
        source = self.root / "escape.md"
        source.write_text("See [Passwd](../../../../../../etc/passwd).\n", encoding="utf-8")

        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(len(errors), 1)
        line_no, link, msg = errors[0]
        self.assertEqual(line_no, 1)
        self.assertIn("outside repository boundary", msg)

    def test_valid_reference_style_links_pass(self):
        target = self.root / "target.md"
        target.write_text("# Target\n", encoding="utf-8")

        source = self.root / "ref_links.md"
        source.write_text(
            """See [Target Guide][guide] and [target][] for details.

[guide]: target.md "Target Title"
[target]: target.md
""",
            encoding="utf-8",
        )
        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(errors, [])

    def test_flags_broken_reference_style_links(self):
        source = self.root / "broken_ref.md"
        source.write_text(
            """See [Broken Guide][missing_file].

[missing_file]: nonexistent.md
""",
            encoding="utf-8",
        )
        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(len(errors), 1)
        self.assertIn("non-existent path", errors[0][2])

    def test_flags_unresolved_reference_labels(self):
        source = self.root / "unresolved_ref.md"
        source.write_text("See [Broken Reference][undefined_label].\n", encoding="utf-8")

        errors = verify_markdown_links.check_markdown_file(source, self.root)
        self.assertEqual(len(errors), 1)
        self.assertIn("Unresolved reference link label", errors[0][2])

    def test_main_cli_success(self):
        target = self.root / "valid.md"
        target.write_text("# Valid doc\n", encoding="utf-8")

        with patch("sys.argv", ["verify_markdown_links.py", "--root", str(self.root)]):
            with patch("sys.stdout"):
                with self.assertRaises(SystemExit) as cm:
                    verify_markdown_links.main()
                self.assertEqual(cm.exception.code, 0)

    def test_main_cli_failure(self):
        target = self.root / "invalid.md"
        target.write_text("[Bad](file:///home/bad/path.md)\n", encoding="utf-8")

        with patch("sys.argv", ["verify_markdown_links.py", "--root", str(self.root)]):
            with patch("sys.stdout"):
                with self.assertRaises(SystemExit) as cm:
                    verify_markdown_links.main()
                self.assertEqual(cm.exception.code, 1)


if __name__ == "__main__":
    unittest.main()
