#!/usr/bin/env python3
"""
Unit tests for scripts/verify_import_hygiene.py.
"""

import os
import shutil
import tempfile
import unittest
from unittest.mock import patch

from scripts.verify_import_hygiene import (
    check_file,
    main,
    sanitize_literals,
    scan_directory,
)


class TestVerifyImportHygiene(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="import_hygiene_test_")

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def _create_java_file(self, filename: str, content: str) -> str:
        path = os.path.join(self.test_dir, filename)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        return path

    def test_clean_file_with_proper_imports_has_no_violations(self):
        content = """package guru.thomasweber.devagent;

import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

public class CleanService {
    private final AtomicReference<String> ref = new AtomicReference<>();
    private final List<String> items = List.of();
}
"""
        file_path = self._create_java_file("CleanService.java", content)
        violations = check_file(file_path)
        self.assertEqual(len(violations), 0)

    def test_detects_inline_atomic_reference_fqn(self):
        content = """package guru.thomasweber.devagent;

public class BadService {
    void doWork() {
        java.util.concurrent.atomic.AtomicReference<String> ref = new java.util.concurrent.atomic.AtomicReference<>();
    }
}
"""
        file_path = self._create_java_file("BadService.java", content)
        violations = check_file(file_path)
        self.assertGreaterEqual(len(violations), 1)
        lines = [v[0] for v in violations]
        self.assertIn(5, lines)

    def test_ignores_fqn_inside_comments(self):
        content = """package guru.thomasweber.devagent;

// Single-line comment with java.util.concurrent.atomic.AtomicReference
/* Block comment with
   org.junit.jupiter.api.Test
*/
public class CommentedService {
    /* inline comment */ int x = 1;
}
"""
        file_path = self._create_java_file("CommentedService.java", content)
        violations = check_file(file_path)
        self.assertEqual(len(violations), 0)

    def test_ignores_fqn_inside_string_literals(self):
        content = """package guru.thomasweber.devagent;

public class StringService {
    private static final String PKG = "guru.thomasweber.devagent.model.TaskRun";
    private static final String ARCH = "java.util.concurrent.atomic.AtomicReference";
}
"""
        file_path = self._create_java_file("StringService.java", content)
        violations = check_file(file_path)
        self.assertEqual(len(violations), 0)

    def test_sanitize_literals_utility(self):
        line = 'String s = "java.util.List" + "another.string";'
        result = sanitize_literals(line)
        self.assertNotIn("java.util.List", result)
        self.assertEqual(result, 'String s = "" + "";')

    def test_detects_fqn_with_diverse_package_prefixes(self):
        content = """package guru.thomasweber.devagent;

public class DiversePrefixes {
    com.fasterxml.jackson.databind.ObjectMapper mapper;
    jakarta.persistence.Entity entity;
    dev.langchain4j.agent.tool.ToolExecutionRequest req;
}
"""
        file_path = self._create_java_file("DiversePrefixes.java", content)
        violations = check_file(file_path)
        self.assertEqual(len(violations), 3)

    def test_string_literal_with_comment_markers_does_not_mask_later_fqn(self):
        content = """package guru.thomasweber.devagent;

public class StringCommentService {
    String url = "https://example.com/api"; java.util.List list;
    String block = "/* not a comment"; java.util.Map map;
}
"""
        file_path = self._create_java_file("StringCommentService.java", content)
        violations = check_file(file_path)
        self.assertEqual(len(violations), 2)
        fqns = [v[1] for v in violations]
        self.assertIn("java.util.List", fqns)
        self.assertIn("java.util.Map", fqns)

    def test_collision_exception_permits_disambiguating_fqn(self):
        content = """package guru.thomasweber.devagent;

import java.util.Date;

public class DateHolder {
    Date utilDate;
    java.sql.Date sqlDate; // Exempt due to collision with imported java.util.Date
}
"""
        file_path = self._create_java_file("DateHolder.java", content)
        violations = check_file(file_path)
        self.assertEqual(len(violations), 0)

    def test_collision_exception_rejects_fqn_without_collision(self):
        content = """package guru.thomasweber.devagent;

public class DateHolderWithoutCollision {
    java.sql.Date sqlDate; // Rejected: no collision in file
}
"""
        file_path = self._create_java_file("DateHolderWithoutCollision.java", content)
        violations = check_file(file_path)
        self.assertEqual(len(violations), 1)
        self.assertEqual(violations[0][1], "java.sql.Date")

    def test_scan_directory_collects_violations_from_multiple_files(self):
        self._create_java_file(
            "pkg1/FileOne.java",
            """package pkg1;
public class FileOne {
    java.io.File file;
}
""",
        )
        self._create_java_file(
            "pkg2/FileTwo.java",
            """package pkg2;
import java.io.File;
public class FileTwo {
    File file;
}
""",
        )
        violations = scan_directory(self.test_dir)
        self.assertEqual(len(violations), 1)
        self.assertTrue(violations[0][0].endswith("FileOne.java"))
        self.assertEqual(violations[0][2], "java.io.File")

    def test_main_passes_when_clean(self):
        self._create_java_file(
            "Clean.java",
            """package clean;
import java.util.List;
public class Clean {
    List<String> list;
}
""",
        )
        with patch("sys.argv", ["verify_import_hygiene.py", "--dir", self.test_dir]):
            exit_code = main()
            self.assertEqual(exit_code, 0)

    def test_main_fails_when_violations_exist(self):
        self._create_java_file(
            "Dirty.java",
            """package dirty;
public class Dirty {
    java.util.List<String> list;
}
""",
        )
        with patch("sys.argv", ["verify_import_hygiene.py", "--dir", self.test_dir]):
            exit_code = main()
            self.assertEqual(exit_code, 1)

    def test_main_fails_on_nonexistent_directory(self):
        nonexistent = os.path.join(self.test_dir, "does_not_exist")
        with patch("sys.argv", ["verify_import_hygiene.py", "--dir", nonexistent]):
            exit_code = main()
            self.assertEqual(exit_code, 1)


if __name__ == "__main__":
    unittest.main()
