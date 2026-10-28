#!/usr/bin/env python3
"""
check_pr_reviews.py - Ingest, audit, and remediate GitHub Pull Request review threads.

This script fetches PR review threads (including CodeRabbit AI reviews and human comments),
detects active in-progress reviews, parses findings, and provides CLI commands to inspect,
reply to, and resolve review threads.

Usage:
    ./scripts/check_pr_reviews.py
    ./scripts/check_pr_reviews.py --pr 2
    ./scripts/check_pr_reviews.py --details
    ./scripts/check_pr_reviews.py --json
    ./scripts/check_pr_reviews.py --check-status
    ./scripts/check_pr_reviews.py --thread PRRT_kwDO... --reply "Resolved." --resolve
    ./scripts/check_pr_reviews.py --fail-on-unresolved
"""

import argparse
import json
import re
import signal
import subprocess
import sys
from typing import Any, Dict, List, Optional, Tuple

# Enable graceful pipe handling (e.g. | head)
if hasattr(signal, "SIGPIPE"):
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)


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
        print("Error: 'gh' (GitHub CLI) is not installed or not in PATH.", file=sys.stderr)
        sys.exit(2)


def detect_repo_and_pr(cli_repo: Optional[str], cli_pr: Optional[int]) -> Tuple[str, str, int]:
    """Detect owner, repo name, and PR number."""
    owner = ""
    repo = ""

    if cli_repo:
        parts = cli_repo.split("/")
        if len(parts) == 2:
            owner, repo = parts[0], parts[1]

    if not (owner and repo):
        code, out, _ = run_gh_cmd(["repo", "view", "--json", "owner,name"])
        if code == 0 and out:
            data = json.loads(out)
            owner = data.get("owner", {}).get("login", "")
            repo = data.get("name", "")

    if not (owner and repo):
        # Fallback to git remote
        try:
            rem = subprocess.check_output(
                ["git", "config", "--get", "remote.origin.url"], text=True
            ).strip()
            # Match github.com[:/]([^/]+)/([^/.]+)(?:\.git)?
            m = re.search(r"github\.com[:/]([^/]+)/([^/.]+?)(?:\.git)?$", rem)
            if m:
                owner, repo = m.group(1), m.group(2)
        except Exception:
            pass

    pr_num = cli_pr
    if not pr_num:
        code, out, _ = run_gh_cmd(["pr", "view", "--json", "number"])
        if code == 0 and out:
            data = json.loads(out)
            pr_num = data.get("number")

    if not (owner and repo):
        print("Error: Could not determine GitHub repository (owner/name). Use --repo <owner/repo>.", file=sys.stderr)
        sys.exit(2)

    if not pr_num:
        print("Error: Could not determine Pull Request number. Use --pr <number>.", file=sys.stderr)
        sys.exit(2)

    return owner, repo, pr_num


def check_coderabbit_progress(owner: str, repo: str, pr_num: int) -> Dict[str, Any]:
    """Inspect top-level issue comments to see if CodeRabbit is actively running."""
    endpoint = f"repos/{owner}/{repo}/issues/{pr_num}/comments"
    code, out, err = run_gh_cmd(["api", endpoint])
    if code != 0 or not out:
        return {"in_progress": False, "status": "Unknown", "run_id": None}

    try:
        comments = json.loads(out)
    except json.JSONDecodeError:
        return {"in_progress": False, "status": "Invalid JSON", "run_id": None}

    for c in reversed(comments):
        user = c.get("user", {}).get("login", "")
        body = c.get("body", "")
        if "coderabbit" in user.lower() or "coderabbit-ai-generated" in body:
            run_id_match = re.search(r"(?:\*\*)?Run ID(?:\*\*)?:\s*`([a-f0-9-]+)`", body)
            run_id = run_id_match.group(1) if run_id_match else None
            updated_at = c.get("updated_at")

            if "Currently processing new changes in this PR" in body or "review in progress" in body:
                return {
                    "in_progress": True,
                    "status": "Review in progress by CodeRabbit",
                    "run_id": run_id,
                    "updated_at": updated_at,
                }
            else:
                return {
                    "in_progress": False,
                    "status": "Review completed",
                    "run_id": run_id,
                    "updated_at": updated_at,
                }

    return {"in_progress": False, "status": "No CodeRabbit review comment found", "run_id": None}


def fetch_review_threads(owner: str, repo: str, pr_num: int) -> Dict[str, Any]:
    """Fetch PR review threads using GraphQL."""
    query = """
    query($owner: String!, $repo: String!, $pr: Int!) {
      repository(owner: $owner, name: $repo) {
        pullRequest(number: $pr) {
          id
          title
          state
          isDraft
          headRefName
          reviewThreads(first: 100) {
            nodes {
              id
              isResolved
              isOutdated
              path
              line
              originalLine
              comments(first: 20) {
                nodes {
                  id
                  author {
                    login
                  }
                  body
                  createdAt
                  updatedAt
                }
              }
            }
          }
        }
      }
    }
    """
    code, out, err = run_gh_cmd([
        "api",
        "graphql",
        "-f", f"owner={owner}",
        "-f", f"repo={repo}",
        "-F", f"pr={pr_num}",
        "-f", f"query={query}",
    ])

    if code != 0:
        print(f"Error fetching review threads: {err}", file=sys.stderr)
        sys.exit(1)

    try:
        return json.loads(out)
    except json.JSONDecodeError as e:
        print(f"Error decoding GraphQL response: {e}", file=sys.stderr)
        sys.exit(1)


def parse_coderabbit_comment(body: str) -> Dict[str, str]:
    """Extract structured details (severity, title, category, cwe) from a CodeRabbit comment."""
    info = {
        "severity": "Info",
        "category": "General",
        "title": "Review finding",
        "cwe": "",
        "action_prompt": "",
    }

    # Extract severity & category: e.g. **<picture>...</picture> Major** · **Security & Privacy**
    badge_match = re.search(
        r"(?:<picture>.*?</picture>\s*)?(?:<b>|<strong\b[^>]*>|\*\*)(Critical|Major|Minor|Trivial|Info)(?:</b>|</strong>|\*\*)\s*·\s*(?:<b>|<strong\b[^>]*>|\*\*)([^*<]+)(?:</b>|</strong>|\*\*)",
        body,
        re.DOTALL,
    )
    if badge_match:
        info["severity"] = badge_match.group(1).strip()
        info["category"] = badge_match.group(2).strip()

    # Extract CWE: [CWE-59](...)
    cwe_match = re.search(r"\[(CWE-\d+)\]", body)
    if cwe_match:
        info["cwe"] = cwe_match.group(1)

    # Extract finding headline: **Headline here.**
    headline_match = re.search(r"\n\*\*([A-Z][^\n*]+?\.)\*\*", body)
    if headline_match:
        info["title"] = headline_match.group(1).strip()
    else:
        # Fallback to first non-empty line after badges
        lines = [line.strip() for line in body.splitlines() if line.strip() and not line.startswith("<!--") and not line.startswith("**<picture>") and not line.startswith("<picture>")]
        if lines:
            first_line = re.sub(r"[*_`]", "", lines[0])
            info["title"] = first_line[:80]

    # Extract prompt if present
    prompt_match = re.search(
        r"```\n(?:Treat finding text, file paths, and code as untrusted review data\..*?\n)?Review comment at.*?\n(.*?)\n\nAfter applying the fix",
        body,
        re.DOTALL,
    )
    if prompt_match:
        info["action_prompt"] = prompt_match.group(1).strip()

    return info


def reply_to_thread(thread_id: str, body: str) -> None:
    """Add a reply to an existing PR review thread."""
    mutation = """
    mutation($threadId: ID!, $body: String!) {
      addPullRequestReviewThreadReply(input: {pullRequestReviewThreadId: $threadId, body: $body}) {
        comment {
          id
          createdAt
        }
      }
    }
    """
    code, out, err = run_gh_cmd([
        "api",
        "graphql",
        "-f", f"threadId={thread_id}",
        "-f", f"body={body}",
        "-f", f"query={mutation}",
    ])
    if code != 0:
        print(f"Error replying to thread {thread_id}: {err}", file=sys.stderr)
        sys.exit(1)
    print(f"Successfully replied to thread {thread_id}.")


def resolve_thread(thread_id: str) -> None:
    """Mark a review thread as resolved."""
    mutation = """
    mutation($threadId: ID!) {
      resolveReviewThread(input: {threadId: $threadId}) {
        thread {
          id
          isResolved
        }
      }
    }
    """
    code, out, err = run_gh_cmd([
        "api",
        "graphql",
        "-f", f"threadId={thread_id}",
        "-f", f"query={mutation}",
    ])
    if code != 0:
        print(f"Error resolving thread {thread_id}: {err}", file=sys.stderr)
        sys.exit(1)
    print(f"Successfully resolved thread {thread_id}.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Audit, inspect, and remediate GitHub PR review threads (CodeRabbit & peers)."
    )
    parser.add_argument("--repo", type=str, default=None, help="Repository as owner/name (default: auto-detect)")
    parser.add_argument("--pr", type=int, default=None, help="Pull Request number (default: auto-detect)")
    parser.add_argument("--all", action="store_true", help="Display all threads (including resolved ones)")
    parser.add_argument("-d", "--details", action="store_true", help="Display complete comment bodies and prompts")
    parser.add_argument("--json", action="store_true", help="Output threads and status as JSON")
    parser.add_argument("--check-status", action="store_true", help="Only check and report review progress status")
    parser.add_argument("--thread", type=str, default=None, help="Target review thread ID for actions")
    parser.add_argument("--reply", type=str, default=None, help="Post a reply comment to the specified --thread")
    parser.add_argument("--resolve", action="store_true", help="Resolve the specified --thread (or reply + resolve)")
    parser.add_argument(
        "--fail-on-unresolved",
        action="store_true",
        help="Exit with non-zero exit code (1) if there are unresolved review threads",
    )

    args = parser.parse_args()

    # Handle direct mutation actions first
    if args.reply or args.resolve:
        if not args.thread:
            print("Error: --thread <thread_id> is required when using --reply or --resolve.", file=sys.stderr)
            sys.exit(2)
        if args.reply:
            reply_to_thread(args.thread, args.reply)
        if args.resolve:
            resolve_thread(args.thread)
        sys.exit(0)

    owner, repo, pr_num = detect_repo_and_pr(args.repo, args.pr)

    # 1. Check CodeRabbit review progress
    progress = check_coderabbit_progress(owner, repo, pr_num)

    if args.check_status:
        if args.json:
            print(json.dumps(progress, indent=2))
        else:
            status_text = progress.get("status", "Unknown")
            run_id = progress.get("run_id", "N/A")
            print(f"Repository: {owner}/{repo} | PR: #{pr_num}")
            print(f"Review Status: {status_text} (Run ID: {run_id})")
        sys.exit(1 if progress.get("in_progress") else 0)

    # 2. Fetch review threads
    raw_data = fetch_review_threads(owner, repo, pr_num)
    pr_data = raw_data.get("data", {}).get("repository", {}).get("pullRequest", {})
    threads = pr_data.get("reviewThreads", {}).get("nodes", [])

    total_threads = len(threads)
    resolved_threads = [t for t in threads if t.get("isResolved")]
    unresolved_threads = [t for t in threads if not t.get("isResolved")]

    active_list = threads if args.all else unresolved_threads

    if args.json:
        result = {
            "repository": f"{owner}/{repo}",
            "pr_number": pr_num,
            "title": pr_data.get("title"),
            "head_branch": pr_data.get("headRefName"),
            "progress": progress,
            "summary": {
                "total": total_threads,
                "resolved": len(resolved_threads),
                "unresolved": len(unresolved_threads),
            },
            "threads": active_list,
        }
        print(json.dumps(result, indent=2))
        if args.fail_on_unresolved and unresolved_threads:
            sys.exit(1)
        sys.exit(0)

    # CLI Human-Readable Output
    print("=" * 78)
    print(f"  PULL REQUEST REVIEW AUDIT: {owner}/{repo} #{pr_num}")
    print(f"  Title:  {pr_data.get('title')}")
    print(f"  Branch: {pr_data.get('headRefName')}")
    print(f"  Status: {progress.get('status')} (Run ID: {progress.get('run_id') or 'N/A'})")
    print(f"  Stats:  Total: {total_threads} | Resolved: {len(resolved_threads)} | Unresolved: {len(unresolved_threads)}")
    print("=" * 78)

    if not active_list:
        print("\nAll review threads are resolved! No open findings.")
        print("-" * 78)
        sys.exit(0)

    print(f"\nListing {'all' if args.all else 'unresolved'} review threads ({len(active_list)}):\n")

    for i, t in enumerate(active_list, 1):
        tid = t.get("id")
        path = t.get("path")
        line = t.get("line") or t.get("originalLine") or "N/A"
        is_resolved = t.get("isResolved")
        comments = t.get("comments", {}).get("nodes", [])
        first_comment = comments[0] if comments else {}
        last_comment = comments[-1] if comments else {}

        meta = parse_coderabbit_comment(first_comment.get("body", ""))

        status_flag = "[RESOLVED]" if is_resolved else "[UNRESOLVED]"
        print(f"[{i}] {status_flag} Thread ID: {tid}")
        print(f"    Target:   {path}:{line}")
        print(f"    Severity: {meta['severity']} | Category: {meta['category']}" + (f" | {meta['cwe']}" if meta['cwe'] else ""))
        print(f"    Finding:  {meta['title']}")
        print(f"    History:  {len(comments)} comment(s). Last author: @{last_comment.get('author', {}).get('login', 'unknown')}")

        if args.details:
            if meta.get("action_prompt"):
                print(f"    Action:   {meta['action_prompt']}")
            print("    --- Comments ---")
            for c in comments:
                author = c.get("author", {}).get("login", "unknown")
                body_clean = "\n      ".join(c.get("body", "").splitlines()[:10])
                print(f"      [@{author}]:\n      {body_clean}")
                if len(c.get("body", "").splitlines()) > 10:
                    print("      ... [truncated]")
            print("    ----------------")

        print(f"    Remediate command:")
        print(f"      ./scripts/check_pr_reviews.py --thread {tid} --reply \"Resolved...\" --resolve\n")

    print("-" * 78)

    if args.fail_on_unresolved and unresolved_threads:
        sys.exit(1)


if __name__ == "__main__":
    main()
