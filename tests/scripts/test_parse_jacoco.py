#!/usr/bin/env python3
"""
Unit tests for scripts/parse_jacoco.py
"""

import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))

import parse_jacoco


class TestParseJacoco(unittest.TestCase):

    def test_format_pct(self):
        self.assertEqual(parse_jacoco.format_pct(0, 0), "0.00%")
        self.assertEqual(parse_jacoco.format_pct(100, 200), "50.00%")
        self.assertEqual(parse_jacoco.format_pct(95, 100), "95.00%")
        self.assertEqual(parse_jacoco.format_pct(1, 3), "33.33%")

    def test_calculate_metrics(self):
        rows = [
            {
                "INSTRUCTION_MISSED": "10",
                "INSTRUCTION_COVERED": "90",
                "BRANCH_MISSED": "2",
                "BRANCH_COVERED": "18",
                "LINE_MISSED": "5",
                "LINE_COVERED": "45",
                "COMPLEXITY_MISSED": "1",
                "COMPLEXITY_COVERED": "9",
                "METHOD_MISSED": "0",
                "METHOD_COVERED": "10",
            },
            {
                "INSTRUCTION_MISSED": "20",
                "INSTRUCTION_COVERED": "80",
                "BRANCH_MISSED": "4",
                "BRANCH_COVERED": "16",
                "LINE_MISSED": "10",
                "LINE_COVERED": "40",
                "COMPLEXITY_MISSED": "2",
                "COMPLEXITY_COVERED": "8",
                "METHOD_MISSED": "1",
                "METHOD_COVERED": "9",
            },
        ]
        metrics = parse_jacoco.calculate_metrics(rows)

        self.assertEqual(metrics["INSTRUCTION"], (170, 200))
        self.assertEqual(metrics["BRANCH"], (34, 40))
        self.assertEqual(metrics["LINE"], (85, 100))
        self.assertEqual(metrics["COMPLEXITY"], (17, 20))
        self.assertEqual(metrics["METHOD"], (19, 20))

    def test_parse_csv_valid_file(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".csv", delete=False) as f:
            f.write("GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED\n")
            f.write("dev-agent,org.quarkus.agent,DevAgent,5,95\n")
            temp_path = Path(f.name)

        try:
            rows = parse_jacoco.parse_csv(temp_path)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["CLASS"], "DevAgent")
            self.assertEqual(rows[0]["INSTRUCTION_COVERED"], "95")
        finally:
            temp_path.unlink(missing_ok=True)

    def test_parse_csv_missing_file_exits_2(self):
        missing_path = Path("/non/existent/path/jacoco.csv")
        with patch("sys.stderr", new_callable=io.StringIO):
            with self.assertRaises(SystemExit) as cm:
                parse_jacoco.parse_csv(missing_path)
            self.assertEqual(cm.exception.code, 2)

    def test_main_quality_gates_pass(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".csv", delete=False) as f:
            f.write("GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n")
            # 96% line coverage (4 missed, 96 covered = 100 total), 92% branch (8 missed, 92 covered = 100 total)
            f.write("dev-agent,org.pkg,App,5,95,8,92,4,96,2,10,1,9\n")
            temp_path = Path(f.name)

        try:
            with patch("sys.argv", ["parse_jacoco.py", "-f", str(temp_path), "--fail-under-gates"]):
                with patch("sys.stdout", new_callable=io.StringIO) as mock_out:
                    parse_jacoco.main()
                    out = mock_out.getvalue()
                    self.assertIn("QUALITY GATE CHECKS PASSED", out)
                    self.assertIn("Line coverage 96.00% >= required quality gate 95.00%", out)
                    self.assertIn("Condition/Branch coverage 92.00% >= required quality gate 90.00%", out)
        finally:
            temp_path.unlink(missing_ok=True)

    def test_main_quality_gates_fail_line(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".csv", delete=False) as f:
            f.write("GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n")
            # 90% line coverage (< 95%), 92% branch
            f.write("dev-agent,org.pkg,App,5,95,8,92,10,90,2,10,1,9\n")
            temp_path = Path(f.name)

        try:
            with patch("sys.argv", ["parse_jacoco.py", "-f", str(temp_path), "--fail-under-gates"]):
                with patch("sys.stdout", new_callable=io.StringIO):
                    with patch("sys.stderr", new_callable=io.StringIO) as mock_err:
                        with self.assertRaises(SystemExit) as cm:
                            parse_jacoco.main()
                        self.assertEqual(cm.exception.code, 1)
                        self.assertIn("Line coverage", mock_err.getvalue())
        finally:
            temp_path.unlink(missing_ok=True)

    def test_main_quality_gates_fail_branch(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".csv", delete=False) as f:
            f.write("GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n")
            # 96% line coverage, 85% branch (< 90%)
            f.write("dev-agent,org.pkg,App,5,95,15,85,4,96,2,10,1,9\n")
            temp_path = Path(f.name)

        try:
            with patch("sys.argv", ["parse_jacoco.py", "-f", str(temp_path), "--fail-under-gates"]):
                with patch("sys.stdout", new_callable=io.StringIO):
                    with patch("sys.stderr", new_callable=io.StringIO) as mock_err:
                        with self.assertRaises(SystemExit) as cm:
                            parse_jacoco.main()
                        self.assertEqual(cm.exception.code, 1)
                        self.assertIn("Condition/Branch coverage", mock_err.getvalue())
        finally:
            temp_path.unlink(missing_ok=True)

    def test_main_fail_under_condition_alias(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".csv", delete=False) as f:
            f.write("GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n")
            # 85% branch (< 90%)
            f.write("dev-agent,org.pkg,App,5,95,15,85,4,96,2,10,1,9\n")
            temp_path = Path(f.name)

        try:
            with patch("sys.argv", ["parse_jacoco.py", "-f", str(temp_path), "--fail-under-condition", "90.0"]):
                with patch("sys.stdout", new_callable=io.StringIO):
                    with patch("sys.stderr", new_callable=io.StringIO) as mock_err:
                        with self.assertRaises(SystemExit) as cm:
                            parse_jacoco.main()
                        self.assertEqual(cm.exception.code, 1)
                        self.assertIn("Condition/Branch coverage", mock_err.getvalue())
        finally:
            temp_path.unlink(missing_ok=True)

    def test_main_empty_csv_with_gates(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".csv", delete=False) as f:
            temp_path = Path(f.name)

        try:
            with patch("sys.argv", ["parse_jacoco.py", "-f", str(temp_path), "--fail-under-gates"]):
                with patch("sys.stderr", new_callable=io.StringIO) as mock_err:
                    with self.assertRaises(SystemExit) as cm:
                        parse_jacoco.main()
                    self.assertEqual(cm.exception.code, 1)
                    self.assertIn("Coverage is 0.00%", mock_err.getvalue())
        finally:
            temp_path.unlink(missing_ok=True)

    def test_main_zero_total_instructions(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".csv", delete=False) as f:
            f.write("GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n")
            f.write("dev-agent,org.pkg,App,0,0,0,0,0,0,0,0,0,0\n")
            temp_path = Path(f.name)

        try:
            with patch("sys.argv", ["parse_jacoco.py", "-f", str(temp_path), "--fail-under-gates"]):
                with patch("sys.stderr", new_callable=io.StringIO) as mock_err:
                    with self.assertRaises(SystemExit) as cm:
                        parse_jacoco.main()
                    self.assertEqual(cm.exception.code, 1)
                    self.assertIn("0 total instructions", mock_err.getvalue())
        finally:
            temp_path.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
