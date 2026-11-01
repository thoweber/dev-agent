#!/usr/bin/env python3
"""
verify_markdown_links.py - Validate that Markdown files contain only valid relative links.

Enforces:
1. Prohibits 'file://' URI schemes.
2. Prohibits machine-specific absolute filesystem paths (/home/, /Users/, etc.).
3. Verifies that relative internal links resolve to existing files or directories.
"""

import argparse
import re
import sys
from pathlib import Path
from typing import List, Tuple

IGNORED_DIRS = {".git", "target", ".idea", "node_modules", ".system_generated", "venv", "__pycache__"}

# Match markdown links: [text](url)
# Avoid false positives in inline code or images when possible
LINK_PATTERN = re.compile(r"(?<!!)\[([^\]]+)\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")


def strip_code_blocks(content: str) -> str:
    """Removes fenced code blocks to prevent parsing code snippets as links."""
    return re.sub(r"```[\s\S]*?```", "", content)


def check_markdown_file(file_path: Path, repo_root: Path) -> List[Tuple[int, str, str]]:
    """
    Checks a single markdown file for link violations.
    Returns a list of (line_num, link, error_message).
    """
    errors: List[Tuple[int, str, str]] = []
    try:
        raw_text = file_path.read_text(encoding="utf-8")
    except Exception as e:
        return [(0, "", f"Could not read file: {e}")]

    lines = raw_text.splitlines()
    in_code_block = False

    for line_idx, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("```"):
            in_code_block = not in_code_block
            continue

        if in_code_block:
            continue

        # Strip inline code spans to avoid checking illustrative syntax examples
        line_clean = re.sub(r"`[^`]*`", "", line)

        for match in LINK_PATTERN.finditer(line_clean):
            link_text = match.group(1)
            raw_url = match.group(2).strip()

            # 1. Prohibit file:// scheme
            if raw_url.lower().startswith("file:"):
                errors.append((line_idx, raw_url, "Absolute 'file://' URI scheme is strictly prohibited"))
                continue

            # 2. Skip external web/email links and pure page anchors
            if raw_url.lower().startswith(("http://", "https://", "mailto:")) or raw_url.startswith("#"):
                continue

            # 3. Prohibit absolute filesystem paths
            if raw_url.startswith(("/", "\\")) or (len(raw_url) > 2 and raw_url[1] == ":" and raw_url[0].isalpha()):
                errors.append((line_idx, raw_url, "Absolute filesystem path is prohibited; use relative paths"))
                continue

            # 4. Verify relative internal path exists
            path_part = raw_url.split("#")[0].split("?")[0]
            if not path_part:
                continue

            target_path = (file_path.parent / path_part).resolve()
            if not target_path.exists():
                errors.append((line_idx, raw_url, f"Relative link targets non-existent path: {path_part}"))

    return errors


def find_markdown_files(root: Path) -> List[Path]:
    """Find all markdown files under root, excluding ignored directories."""
    md_files = []
    for path in root.rglob("*.md"):
        if any(ignored in path.parts for ignored in IGNORED_DIRS):
            continue
        md_files.append(path)
    return sorted(md_files)


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify that Markdown links are relative and resolve properly.")
    parser.add_argument("--root", type=Path, default=Path("."), help="Repository root directory to scan")
    parser.add_argument("--file", type=Path, default=None, help="Specific markdown file to check")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")
    args = parser.parse_args()

    repo_root = args.root.resolve()

    if args.file:
        files = [args.file.resolve()]
    else:
        files = find_markdown_files(repo_root)

    total_files = len(files)
    total_violations = 0
    failing_files = 0

    if args.verbose:
        print(f"Scanning {total_files} Markdown file(s) for link integrity...")

    for md_file in files:
        rel_display = md_file.relative_to(repo_root) if md_file.is_relative_to(repo_root) else md_file
        errors = check_markdown_file(md_file, repo_root)

        if errors:
            failing_files += 1
            total_violations += len(errors)
            print(f"\n[FAIL] {rel_display}:")
            for line_no, link, msg in errors:
                print(f"  Line {line_no}: [{link}] -> {msg}")
        elif args.verbose:
            print(f"[PASS] {rel_display}")

    print("\n" + "=" * 70)
    if total_violations == 0:
        print(f"MARKDOWN LINK INTEGRITY CHECK PASSED: {total_files} file(s) scanned, 0 violations.")
        print("=" * 70)
        sys.exit(0)
    else:
        print(f"MARKDOWN LINK INTEGRITY CHECK FAILED:")
        print(f"  {total_violations} violation(s) found across {failing_files} of {total_files} file(s).")
        print("=" * 70)
        sys.exit(1)


if __name__ == "__main__":
    main()
