#!/usr/bin/env python3
"""
Unit tests for scripts/check_pr_reviews.py
"""

import json
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))

import check_pr_reviews


class TestCheckPrReviews(unittest.TestCase):

    # -------------------------------------------------------------------------
    # extract_outside_diff_section tests
    # -------------------------------------------------------------------------

    def test_extract_outside_diff_section_basic(self):
        body = """
## Summary
Some summary text.

## Outside diff range comments
<details>
<summary><code>App.java</code> · Minor · Clean up</summary>
<blockquote>Body</blockquote>
</details>

---
## Next steps
Review details here.
"""
        section = check_pr_reviews.extract_outside_diff_section(body)
        self.assertIsNotNone(section)
        self.assertIn("<code>App.java</code>", section)
        self.assertIn("<blockquote>Body</blockquote>", section)
        self.assertNotIn("## Next steps", section)

    def test_extract_outside_diff_section_with_code_fences_containing_separators(self):
        body = """
## Outside diff range comments
<details>
<summary><code>App.java</code></summary>
<blockquote>
```java
// Inside code block:
---
## Not a heading
int x = 42;
```
After code block.
</blockquote>
</details>

---
## Real Next Section
This must be excluded.
"""
        section = check_pr_reviews.extract_outside_diff_section(body)
        self.assertIsNotNone(section)
        self.assertIn("int x = 42;", section)
        self.assertIn("After code block.", section)
        self.assertNotIn("## Real Next Section", section)

    def test_extract_outside_diff_section_with_tilde_fences(self):
        body = """
## Outside diff comments
~~~markdown
---
## Inside tilde fence
~~~
Text after tilde fence.

## Outer Heading
Should terminate here.
"""
        section = check_pr_reviews.extract_outside_diff_section(body)
        self.assertIsNotNone(section)
        self.assertIn("Inside tilde fence", section)
        self.assertIn("Text after tilde fence.", section)
        self.assertNotIn("Should terminate here.", section)

    def test_extract_outside_diff_section_missing_heading(self):
        body = "## Regular PR Description without outside diff"
        section = check_pr_reviews.extract_outside_diff_section(body)
        self.assertIsNone(section)

    def test_extract_outside_diff_section_alternate_heading(self):
        body = """
## Outside diff comments
Content under alternate heading.

---
"""
        section = check_pr_reviews.extract_outside_diff_section(body)
        self.assertIsNotNone(section)
        self.assertIn("Content under alternate heading.", section)

    # -------------------------------------------------------------------------
    # parse_coderabbit_comment tests
    # -------------------------------------------------------------------------

    def test_parse_coderabbit_comment_badges_and_headline(self):
        body = """
<picture><img src="badge.png"></picture> **Critical** · **Security & Privacy**
[CWE-59](https://cwe.mitre.org/data/definitions/59.html)

**Improper link resolution leads to directory traversal.**

Detailed vulnerability description here.
"""
        info = check_pr_reviews.parse_coderabbit_comment(body)
        self.assertEqual(info["severity"], "Critical")
        self.assertEqual(info["category"], "Security & Privacy")
        self.assertEqual(info["cwe"], "CWE-59")
        self.assertEqual(info["title"], "Improper link resolution leads to directory traversal.")

    def test_parse_coderabbit_comment_html_tags_and_fallback_title(self):
        body = """
<b>Major</b> · <strong>Architecture</strong>

This is a regular finding without a bold headline format.
"""
        info = check_pr_reviews.parse_coderabbit_comment(body)
        self.assertEqual(info["severity"], "Major")
        self.assertEqual(info["category"], "Architecture")
        self.assertEqual(info["cwe"], "")
        self.assertIn("This is a regular finding", info["title"])

    # -------------------------------------------------------------------------
    # is_coderabbit_status_comment tests
    # -------------------------------------------------------------------------

    def test_is_coderabbit_status_comment(self):
        self.assertTrue(
            check_pr_reviews.is_coderabbit_status_comment(
                "coderabbitai[bot]", "Currently processing new changes in this PR..."
            )
        )
        self.assertTrue(
            check_pr_reviews.is_coderabbit_status_comment(
                "some-user", "<!-- coderabbit-ai-generated --> Review in progress"
            )
        )
        self.assertTrue(
            check_pr_reviews.is_coderabbit_status_comment(
                "coderabbitai", "Review profile: Chill\nRun ID: abc-123"
            )
        )
        self.assertFalse(
            check_pr_reviews.is_coderabbit_status_comment(
                "developer", "Looks good to me, approved!"
            )
        )

    # -------------------------------------------------------------------------
    # check_coderabbit_progress tests (Streaming JSON Decoding)
    # -------------------------------------------------------------------------

    @patch("check_pr_reviews.run_gh_cmd")
    def test_check_coderabbit_progress_active(self, mock_gh):
        # Emulate concatenated JSON stream returned by 'gh api --paginate'
        chunk1 = json.dumps([
            {
                "user": {"login": "human"},
                "body": "First comment",
            }
        ])
        chunk2 = json.dumps([
            {
                "user": {"login": "coderabbitai[bot]"},
                "body": "Currently processing new changes in this PR\n**Run ID**: `c1a2b3d4-5678-4a1b-8cde-123456789abc`",
                "updated_at": "2026-10-29T00:00:00Z",
            }
        ])
        mock_gh.return_value = (0, chunk1 + "\n" + chunk2, "")

        progress = check_pr_reviews.check_coderabbit_progress("owner", "repo", 42)
        self.assertTrue(progress["in_progress"])
        self.assertEqual(progress["run_id"], "c1a2b3d4-5678-4a1b-8cde-123456789abc")
        self.assertIn("Review in progress", progress["status"])

    @patch("check_pr_reviews.run_gh_cmd")
    def test_check_coderabbit_progress_completed(self, mock_gh):
        comment = {
            "user": {"login": "coderabbitai[bot]"},
            "body": "This is an auto-generated comment\nReview profile: Chill\nRun ID: `d1e2f3a4-5678-4a1b-8cde-987654321abc`",
            "updated_at": "2026-10-29T00:05:00Z",
        }
        mock_gh.return_value = (0, json.dumps([comment]), "")

        progress = check_pr_reviews.check_coderabbit_progress("owner", "repo", 42)
        self.assertFalse(progress["in_progress"])
        self.assertEqual(progress["run_id"], "d1e2f3a4-5678-4a1b-8cde-987654321abc")
        self.assertEqual(progress["status"], "Review completed")

    @patch("check_pr_reviews.run_gh_cmd")
    def test_check_coderabbit_progress_error_handling(self, mock_gh):
        mock_gh.return_value = (1, "", "Not found")
        progress = check_pr_reviews.check_coderabbit_progress("owner", "repo", 42)
        self.assertIsNone(progress["in_progress"])
        self.assertIn("API call failed", progress["status"])

    # -------------------------------------------------------------------------
    # fetch_outside_diff_comments tests
    # -------------------------------------------------------------------------

    @patch("check_pr_reviews.run_gh_cmd")
    def test_fetch_outside_diff_comments(self, mock_gh):
        review_body = """
## Outside diff range comments
<details>
<summary><code>src/App.java</code> · Critical · Null Pointer Risk</summary>
<blockquote>
<!-- cr-comment:prr-comment-1 -->
**Logic Defect**

```
Review comment at Line 42
Null pointer access

After applying the fix
```
Verify null safety.
</blockquote>
</details>
"""
        response = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "reviews": {
                            "nodes": [
                                {
                                    "id": "PRR_kwDO123",
                                    "author": {"login": "coderabbitai[bot]"},
                                    "body": review_body,
                                    "submittedAt": "2026-10-29T00:10:00Z",
                                    "state": "COMMENTED",
                                }
                            ]
                        }
                    }
                }
            }
        }
        mock_gh.return_value = (0, json.dumps(response), "")

        findings = check_pr_reviews.fetch_outside_diff_comments("owner", "repo", 1)
        self.assertEqual(len(findings), 1)
        f = findings[0]
        self.assertEqual(f["id"], "cr-comment:prr-comment-1")
        self.assertEqual(f["review_id"], "PRR_kwDO123")
        self.assertEqual(f["target"], "src/App.java")
        self.assertEqual(f["severity"], "Critical")
        self.assertEqual(f["title"], "Null Pointer Risk")
        self.assertEqual(f["action_prompt"], "Null pointer access")
        self.assertIn("**Logic Defect**", f["body"])

    @patch("check_pr_reviews.run_gh_cmd")
    def test_fetch_outside_diff_comments_no_cr_reviews(self, mock_gh):
        response = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "reviews": {
                            "nodes": [
                                {
                                    "id": "PRR_1",
                                    "author": {"login": "human"},
                                    "body": "LGTM",
                                }
                            ]
                        }
                    }
                }
            }
        }
        mock_gh.return_value = (0, json.dumps(response), "")
        findings = check_pr_reviews.fetch_outside_diff_comments("owner", "repo", 1)
        self.assertEqual(findings, [])

    # -------------------------------------------------------------------------
    # detect_repo_and_pr tests
    # -------------------------------------------------------------------------

    def test_detect_repo_and_pr_cli_args(self):
        owner, repo, pr = check_pr_reviews.detect_repo_and_pr("my-org/my-project", 123)
        self.assertEqual(owner, "my-org")
        self.assertEqual(repo, "my-project")
        self.assertEqual(pr, 123)


if __name__ == "__main__":
    unittest.main()
