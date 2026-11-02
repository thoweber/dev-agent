#!/usr/bin/env python3
"""
check_sonarcloud.py - Query and audit SonarCloud Quality Gate status and open issues.

Fetches Quality Gate results and open issues for the repository and active Pull Request
from the SonarCloud Web API.

Usage:
    ./scripts/check_sonarcloud.py
    ./scripts/check_sonarcloud.py --check-status
    ./scripts/check_sonarcloud.py --issues
    ./scripts/check_sonarcloud.py --details
    ./scripts/check_sonarcloud.py --pr 6
    ./scripts/check_sonarcloud.py --json
    ./scripts/check_sonarcloud.py --fail-on-error
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

SONARCLOUD_API_BASE = "https://sonarcloud.io/api"
DEFAULT_PROJECT_KEY = "thoweber_dev-agent"


def run_gh_cmd(args: List[str]) -> Tuple[int, str, str]:
    """Execute a gh CLI command and return (returncode, stdout, stderr)."""
    try:
        proc = subprocess.run(
            ["gh"] + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        return proc.returncode, proc.stdout.strip(), proc.stderr.strip()
    except FileNotFoundError:
        return 127, "", "gh command not found"


def detect_pr_number(cli_pr: Optional[int]) -> Optional[int]:
    """Detect current PR number from CLI argument or via gh CLI."""
    if cli_pr:
        return cli_pr
    code, out, _ = run_gh_cmd(["pr", "view", "--json", "number"])
    if code == 0 and out:
        try:
            data = json.loads(out)
            return data.get("number")
        except json.JSONDecodeError:
            pass
    return None


def detect_project_key(cli_project: Optional[str]) -> str:
    """Detect SonarCloud project key from argument, pom.xml, or fallback to default."""
    if cli_project:
        return cli_project
    pom_path = os.path.join(os.getcwd(), "pom.xml")
    if os.path.isfile(pom_path):
        try:
            with open(pom_path, "r", encoding="utf-8") as f:
                content = f.read()
            m = re.search(r"<sonar\.projectKey>([^<]+)</sonar\.projectKey>", content)
            if m:
                return m.group(1).strip()
        except OSError:
            pass
    return DEFAULT_PROJECT_KEY


def make_sonarcloud_request(endpoint: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """Perform a GET request to the SonarCloud Web API."""
    query_string = urllib.parse.urlencode(params)
    url = f"{SONARCLOUD_API_BASE}/{endpoint}?{query_string}"

    req = urllib.request.Request(url)
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "dev-agent-sonar-checker/1.0")

    token = os.environ.get("SONAR_TOKEN")
    if token:
        # SonarCloud supports Basic Auth using token as username with empty password
        auth_bytes = f"{token}:".encode("utf-8")
        auth_header = f"Basic {base64.b64encode(auth_bytes).decode('utf-8')}"
        req.add_header("Authorization", auth_header)

    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            content = resp.read().decode("utf-8")
            return json.loads(content)
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"Error: SonarCloud HTTP {e.code} for URL {url}: {err_body}", file=sys.stderr)
        sys.exit(2)
    except urllib.error.URLError as e:
        print(f"Error: Failed to connect to SonarCloud ({url}): {e.reason}", file=sys.stderr)
        sys.exit(2)
    except Exception as e:
        print(f"Error: Unexpected failure contacting SonarCloud: {e}", file=sys.stderr)
        sys.exit(2)


def fetch_quality_gate(project_key: str, pr_num: Optional[int]) -> Dict[str, Any]:
    """Fetch Quality Gate status."""
    params: Dict[str, Any] = {"projectKey": project_key}
    if pr_num:
        params["pullRequest"] = pr_num
    return make_sonarcloud_request("qualitygates/project_status", params)


def fetch_issues(project_key: str, pr_num: Optional[int], resolved: bool = False) -> List[Dict[str, Any]]:
    """Fetch issues for project or PR."""
    params: Dict[str, Any] = {
        "componentKeys": project_key,
        "resolved": "true" if resolved else "false",
        "ps": 100,
    }
    if pr_num:
        params["pullRequest"] = pr_num
    data = make_sonarcloud_request("issues/search", params)
    return data.get("issues", [])


def format_condition(cond: Dict[str, Any]) -> str:
    """Format a single Quality Gate condition line."""
    metric = cond.get("metricKey", "")
    comparator = cond.get("comparator", "")
    threshold = cond.get("errorThreshold", "")
    actual = cond.get("actualValue", "")
    status = cond.get("status", "")
    sym = ">" if comparator == "GT" else ("<" if comparator == "LT" else comparator)
    return f"  [{status:5s}] {metric:32s} actual={actual:<6s} threshold={sym} {threshold}"


def print_summary(
    project_key: str,
    pr_num: Optional[int],
    qg_data: Dict[str, Any],
    issues: List[Dict[str, Any]],
    show_details: bool = False,
) -> None:
    """Print a clean human-readable audit summary."""
    status = qg_data.get("projectStatus", {}).get("status", "UNKNOWN")
    conditions = qg_data.get("projectStatus", {}).get("conditions", [])

    print("=" * 80)
    print("                     SONARCLOUD QUALITY GATE AUDIT")
    print("=" * 80)
    print(f"Project Key:  {project_key}")
    if pr_num:
        print(f"Pull Request: #{pr_num}")
    print(f"Status:       {status}")
    print(f"Open Issues:  {len(issues)}")
    print("-" * 80)
    print("QUALITY GATE CONDITIONS:")
    for c in conditions:
        print(format_condition(c))
    print("-" * 80)

    if issues:
        print(f"OPEN ISSUES ({len(issues)}):")
        for idx, issue in enumerate(issues, 1):
            key = issue.get("key", "")
            rule = issue.get("rule", "")
            severity = issue.get("severity", "")
            msg = issue.get("message", "")
            comp = issue.get("component", "").replace(f"{project_key}:", "")
            line = issue.get("line", "?")
            print(f"  {idx}. [{severity}] {rule} - {comp}:{line}")
            print(f"     \"{msg}\" (Key: {key})")
        print("-" * 80)
    elif show_details:
        print("No open issues detected.")
        print("-" * 80)


def main() -> None:
    parser = argparse.ArgumentParser(description="Query SonarCloud Quality Gate status and issues.")
    parser.add_argument("--project", help="SonarCloud project key (default: thoweber_dev-agent)")
    parser.add_argument("--pr", type=int, help="Pull Request number (auto-detected if omitted)")
    parser.add_argument("--check-status", action="store_true", help="Check Quality Gate status")
    parser.add_argument("--issues", action="store_true", help="List open issues")
    parser.add_argument("--details", action="store_true", help="Show full conditions and issue details")
    parser.add_argument("--json", action="store_true", help="Output raw JSON")
    parser.add_argument("--fail-on-error", action="store_true", help="Exit code 1 if status is ERROR or issues exist")

    args = parser.parse_args()

    project_key = detect_project_key(args.project)
    pr_num = detect_pr_number(args.pr)

    qg_data = fetch_quality_gate(project_key, pr_num)
    issues = fetch_issues(project_key, pr_num) if (args.issues or args.details or not args.check_status) else []

    if args.json:
        result = {
            "projectKey": project_key,
            "pullRequest": pr_num,
            "qualityGate": qg_data.get("projectStatus", {}),
            "issues": issues,
        }
        print(json.dumps(result, indent=2))
    else:
        print_summary(project_key, pr_num, qg_data, issues, show_details=args.details or args.issues)

    status = qg_data.get("projectStatus", {}).get("status", "UNKNOWN")
    if args.fail_on_error and (status == "ERROR" or len(issues) > 0):
        sys.exit(1)

    sys.exit(0)


if __name__ == "__main__":
    main()
