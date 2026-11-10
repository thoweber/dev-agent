#!/usr/bin/env python3
"""
Unit tests for scripts/check_sonarcloud.py.
"""

import io
import json
import os
import sys
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

import scripts.check_sonarcloud as sc


class TestCheckSonarcloud(unittest.TestCase):

    def test_detect_pr_number_from_cli(self):
        self.assertEqual(sc.detect_pr_number(42), 42)

    @patch("scripts.check_sonarcloud.run_gh_cmd")
    def test_detect_pr_number_from_gh(self, mock_gh):
        mock_gh.return_value = (0, '{"number": 6}', "")
        self.assertEqual(sc.detect_pr_number(None), 6)

    @patch("scripts.check_sonarcloud.run_gh_cmd")
    def test_detect_pr_number_not_found(self, mock_gh):
        mock_gh.return_value = (1, "", "not found")
        self.assertIsNone(sc.detect_pr_number(None))

    def test_detect_branch_from_cli(self):
        self.assertEqual(sc.detect_branch("feature/my-branch"), "feature/my-branch")

    @patch("subprocess.run")
    def test_detect_branch_from_git(self, mock_run):
        mock_proc = MagicMock()
        mock_proc.returncode = 0
        mock_proc.stdout = "feature/TASK-008-test\n"
        mock_run.return_value = mock_proc
        self.assertEqual(sc.detect_branch(None), "feature/TASK-008-test")

    @patch("subprocess.run")
    def test_detect_branch_detached_head(self, mock_run):
        mock_proc = MagicMock()
        mock_proc.returncode = 0
        mock_proc.stdout = "HEAD\n"
        mock_run.return_value = mock_proc
        self.assertIsNone(sc.detect_branch(None))

    @patch("subprocess.run", side_effect=FileNotFoundError)
    def test_detect_branch_git_not_found(self, mock_run):
        self.assertIsNone(sc.detect_branch(None))

    def test_detect_project_key_from_cli(self):
        self.assertEqual(sc.detect_project_key("custom_key"), "custom_key")

    @patch("os.path.isfile", return_value=True)
    @patch("builtins.open", new_callable=unittest.mock.mock_open, read_data="<sonar.projectKey>my_proj</sonar.projectKey>")
    def test_detect_project_key_from_pom(self, mock_open, mock_isfile):
        self.assertEqual(sc.detect_project_key(None), "my_proj")

    @patch("os.path.isfile", return_value=False)
    def test_detect_project_key_default(self, mock_isfile):
        self.assertEqual(sc.detect_project_key(None), sc.DEFAULT_PROJECT_KEY)

    @patch("urllib.request.urlopen")
    def test_make_sonarcloud_request_success(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.read.return_value = b'{"status": "OK"}'
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        res = sc.make_sonarcloud_request("test/endpoint", {"k": "v"})
        self.assertEqual(res, {"status": "OK"})

    @patch("os.environ.get", return_value="dummy_token")
    @patch("urllib.request.urlopen")
    def test_make_sonarcloud_request_with_token(self, mock_urlopen, mock_env):
        mock_resp = MagicMock()
        mock_resp.read.return_value = b'{"status": "OK"}'
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        res = sc.make_sonarcloud_request("test/endpoint", {})
        self.assertEqual(res, {"status": "OK"})

    @patch("urllib.request.urlopen", side_effect=urllib.error.HTTPError("url", 401, "Unauthorized", {}, io.BytesIO(b"denied")))
    def test_make_sonarcloud_request_http_error(self, mock_urlopen):
        with self.assertRaises(SystemExit) as cm:
            sc.make_sonarcloud_request("test/endpoint", {})
        self.assertEqual(cm.exception.code, 2)

    @patch("urllib.request.urlopen", side_effect=urllib.error.URLError("Connection refused"))
    def test_make_sonarcloud_request_url_error(self, mock_urlopen):
        with self.assertRaises(SystemExit) as cm:
            sc.make_sonarcloud_request("test/endpoint", {})
        self.assertEqual(cm.exception.code, 2)

    @patch("scripts.check_sonarcloud.make_sonarcloud_request")
    def test_fetch_quality_gate_pr(self, mock_req):
        mock_req.return_value = {"projectStatus": {"status": "OK"}}
        res = sc.fetch_quality_gate("my_proj", pr_num=6)
        self.assertEqual(res, {"projectStatus": {"status": "OK"}})
        mock_req.assert_called_with("qualitygates/project_status", {"projectKey": "my_proj", "pullRequest": 6})

    @patch("scripts.check_sonarcloud.make_sonarcloud_request")
    def test_fetch_quality_gate_branch(self, mock_req):
        mock_req.return_value = {"projectStatus": {"status": "OK"}}
        res = sc.fetch_quality_gate("my_proj", branch="feature/test")
        self.assertEqual(res, {"projectStatus": {"status": "OK"}})
        mock_req.assert_called_with("qualitygates/project_status", {"projectKey": "my_proj", "branch": "feature/test"})

    @patch("scripts.check_sonarcloud.make_sonarcloud_request")
    def test_fetch_issues(self, mock_req):
        mock_req.return_value = {"issues": [{"key": "ISSUE-1"}]}
        res = sc.fetch_issues("my_proj", pr_num=6)
        self.assertEqual(res, [{"key": "ISSUE-1"}])

    @patch("scripts.check_sonarcloud.make_sonarcloud_request")
    def test_fetch_issues_with_branch(self, mock_req):
        mock_req.return_value = {"issues": [{"key": "ISSUE-B"}]}
        res = sc.fetch_issues("my_proj", branch="main")
        self.assertEqual(res, [{"key": "ISSUE-B"}])

    @patch("scripts.check_sonarcloud.make_sonarcloud_request")
    def test_fetch_hotspots(self, mock_req):
        mock_req.return_value = {"hotspots": [{"key": "HOTSPOT-1", "ruleKey": "javasecurity:S5144"}], "paging": {"total": 1}}
        res = sc.fetch_hotspots("my_proj", pr_num=8)
        self.assertEqual(len(res), 1)
        self.assertEqual(res[0]["key"], "HOTSPOT-1")
        mock_req.assert_called_with("hotspots/search", {"projectKey": "my_proj", "ps": 100, "p": 1, "status": "TO_REVIEW", "pullRequest": 8})

    def test_format_condition(self):
        cond = {
            "metricKey": "new_coverage",
            "comparator": "LT",
            "errorThreshold": "95",
            "actualValue": "96.5",
            "status": "OK",
        }
        formatted = sc.format_condition(cond)
        self.assertIn("OK", formatted)
        self.assertIn("new_coverage", formatted)
        self.assertIn("96.5", formatted)
        self.assertIn("<", formatted)

    def test_print_summary(self):
        qg_data = {
            "projectStatus": {
                "status": "OK",
                "conditions": [
                    {"metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "95", "actualValue": "96.5", "status": "OK"}
                ]
            }
        }
        issues = [
            {"key": "K1", "rule": "java:S123", "severity": "MAJOR", "message": "fix this", "component": "thoweber_dev-agent:src/App.java", "line": 42}
        ]
        hotspots = [
            {"key": "H1", "ruleKey": "javasecurity:S5144", "securityCategory": "sql-injection", "vulnerabilityProbability": "HIGH", "message": "review this", "component": "thoweber_dev-agent:src/Db.java", "line": 10}
        ]
        buf = io.StringIO()
        with patch("sys.stdout", buf):
            sc.print_summary("thoweber_dev-agent", 6, None, qg_data, issues, hotspots, show_details=True)
        out = buf.getvalue()
        self.assertIn("SONARCLOUD QUALITY GATE AUDIT", out)
        self.assertIn("thoweber_dev-agent", out)
        self.assertIn("fix this", out)
        self.assertIn("SECURITY HOTSPOTS (1):", out)
        self.assertIn("review this", out)

    def test_print_summary_failed_conditions(self):
        qg_data = {
            "projectStatus": {
                "status": "ERROR",
                "conditions": [
                    {"metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "95", "actualValue": "80.0", "status": "ERROR"}
                ]
            }
        }
        buf = io.StringIO()
        with patch("sys.stdout", buf):
            sc.print_summary("thoweber_dev-agent", None, "feature/failing", qg_data, [], [], show_details=False)
        out = buf.getvalue()
        self.assertIn("FAILED CONDITIONS (1):", out)
        self.assertIn("new_coverage", out)
        self.assertIn("Branch:            feature/failing", out)

    @patch("scripts.check_sonarcloud.fetch_quality_gate")
    @patch("scripts.check_sonarcloud.fetch_issues")
    @patch("scripts.check_sonarcloud.fetch_hotspots")
    def test_main_cli_json(self, mock_hotspots, mock_issues, mock_qg):
        mock_qg.return_value = {"projectStatus": {"status": "OK"}}
        mock_issues.return_value = []
        mock_hotspots.return_value = []
        buf = io.StringIO()
        with patch.object(sys, "argv", ["check_sonarcloud.py", "--pr", "6", "--json"]), patch("sys.stdout", buf):
            with self.assertRaises(SystemExit) as cm:
                sc.main()
            self.assertEqual(cm.exception.code, 0)
        data = json.loads(buf.getvalue())
        self.assertEqual(data["pullRequest"], 6)
        self.assertEqual(data["qualityGate"]["status"], "OK")
        self.assertEqual(data["hotspots"], [])

    @patch("scripts.check_sonarcloud.fetch_quality_gate")
    @patch("scripts.check_sonarcloud.fetch_issues")
    @patch("scripts.check_sonarcloud.fetch_hotspots")
    def test_main_cli_check_status_concise(self, mock_hotspots, mock_issues, mock_qg):
        mock_qg.return_value = {"projectStatus": {"status": "OK"}}
        buf = io.StringIO()
        with patch.object(sys, "argv", ["check_sonarcloud.py", "--pr", "8", "--check-status"]), patch("sys.stdout", buf):
            with self.assertRaises(SystemExit) as cm:
                sc.main()
            self.assertEqual(cm.exception.code, 0)
        out = buf.getvalue().strip()
        self.assertEqual(out, "Repository: thoweber_dev-agent | PR: #8 | Quality Gate: OK")

    @patch("scripts.check_sonarcloud.fetch_quality_gate")
    @patch("scripts.check_sonarcloud.fetch_issues")
    @patch("scripts.check_sonarcloud.fetch_hotspots")
    def test_main_cli_fail_on_error(self, mock_hotspots, mock_issues, mock_qg):
        mock_qg.return_value = {"projectStatus": {"status": "ERROR"}}
        mock_issues.return_value = []
        mock_hotspots.return_value = []
        with patch.object(sys, "argv", ["check_sonarcloud.py", "--pr", "6", "--check-status", "--fail-on-error"]), patch("sys.stdout", io.StringIO()):
            with self.assertRaises(SystemExit) as cm:
                sc.main()
            self.assertEqual(cm.exception.code, 1)

    @patch("scripts.check_sonarcloud.fetch_quality_gate")
    @patch("scripts.check_sonarcloud.fetch_issues")
    @patch("scripts.check_sonarcloud.fetch_hotspots")
    def test_main_cli_fail_on_error_with_open_issues(self, mock_hotspots, mock_issues, mock_qg):
        mock_qg.return_value = {"projectStatus": {"status": "OK"}}
        mock_issues.return_value = [{"key": "issue-1"}]
        mock_hotspots.return_value = []
        with patch.object(sys, "argv", ["check_sonarcloud.py", "--pr", "6", "--check-status", "--fail-on-error"]), patch("sys.stdout", io.StringIO()):
            with self.assertRaises(SystemExit) as cm:
                sc.main()
            self.assertEqual(cm.exception.code, 1)

    @patch("scripts.check_sonarcloud.fetch_quality_gate")
    @patch("scripts.check_sonarcloud.fetch_issues")
    @patch("scripts.check_sonarcloud.fetch_hotspots")
    def test_main_cli_fail_on_error_with_open_hotspots(self, mock_hotspots, mock_issues, mock_qg):
        mock_qg.return_value = {"projectStatus": {"status": "OK"}}
        mock_issues.return_value = []
        mock_hotspots.return_value = [{"key": "hotspot-1"}]
        with patch.object(sys, "argv", ["check_sonarcloud.py", "--pr", "6", "--check-status", "--fail-on-error"]), patch("sys.stdout", io.StringIO()):
            with self.assertRaises(SystemExit) as cm:
                sc.main()
            self.assertEqual(cm.exception.code, 1)

    @patch("scripts.check_sonarcloud.make_sonarcloud_request")
    def test_fetch_issues_pagination(self, mock_req):
        mock_req.side_effect = [
            {"total": 2, "issues": [{"key": "issue-1"}]},
            {"total": 2, "issues": [{"key": "issue-2"}]},
        ]
        issues = sc.fetch_issues("my-proj", 6)
        self.assertEqual(len(issues), 2)
        self.assertEqual(issues[0]["key"], "issue-1")
        self.assertEqual(issues[1]["key"], "issue-2")
        self.assertEqual(mock_req.call_count, 2)

    @patch("scripts.check_sonarcloud.make_sonarcloud_request")
    def test_fetch_issues_respects_max_ceiling(self, mock_req):
        mock_req.return_value = {"total": 20000, "issues": [{"key": f"issue-{i}"} for i in range(100)]}
        with patch("scripts.check_sonarcloud.MAX_SONARCLOUD_RESULTS", 200):
            issues = sc.fetch_issues("my-proj", 6)
            self.assertEqual(len(issues), 200)
            self.assertEqual(mock_req.call_count, 2)


if __name__ == "__main__":
    unittest.main()
