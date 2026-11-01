#!/usr/bin/env python3
"""
verify_markdown_links.py - Validate that Markdown files contain only valid relative links.

Enforces:
1. Prohibits 'file://' URI schemes.
2. Prohibits machine-specific absolute filesystem paths (/home/, /Users/, etc.).
3. Verifies that relative internal links resolve to paths within repo_root.
4. Verifies that relative internal links resolve to existing files or directories.
5. Validates both inline links [text](url) and reference-style links [text][ref].
6. Strictly matches opening code fence character and length before closing blocks.
"""

import argparse
import re
import sys
from pathlib import Path
from typing import Dict, List, Tuple

IGNORED_DIRS = {".git", "target", ".idea", "node_modules", ".system_generated", "venv", "__pycache__"}

# Match markdown inline links: [text](url)
LINK_PATTERN = re.compile(r"(?<!!)\[([^\]]+)\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")

# Match reference definitions: [label]: url "optional title"
REF_DEF_PATTERN = re.compile(r"^\s*\[([^\]]+)\]:\s*(\S+)(?:\s+[\"'(].*[\"')])?\s*$")

# Match reference-style links: [text][ref] or [ref][] (skip indexing expressions like matrix[i][j])
REF_LINK_PATTERN = re.compile(r"(?<![\w!])\[([^\]]+)\]\[([^\]]*)\]")


def validate_target_url(
    raw_url: str,
    file_path: Path,
    repo_root: Path,
    line_idx: int,
) -> List[Tuple[int, str, str]]:
    """Validates a single raw target URL against repository link standards."""
    errors: List[Tuple[int, str, str]] = []
    clean_url = raw_url.strip()
    resolved_root = repo_root.resolve()

    # 1. Prohibit file:// scheme
    if clean_url.lower().startswith("file:"):
        errors.append((line_idx, clean_url, "Absolute 'file://' URI scheme is strictly prohibited"))
        return errors

    # 2. Skip external web/email links and pure page anchors
    if clean_url.lower().startswith(("http://", "https://", "mailto:")) or clean_url.startswith("#"):
        return errors

    # 3. Prohibit absolute filesystem paths
    if clean_url.startswith(("/", "\\")) or (len(clean_url) > 2 and clean_url[1] == ":" and clean_url[0].isalpha()):
        errors.append((line_idx, clean_url, "Absolute filesystem path is prohibited; use relative paths"))
        return errors

    # 4. Extract path part (strip anchors and query params)
    path_part = clean_url.split("#")[0].split("?")[0]
    if not path_part:
        return errors

    try:
        target_path = (file_path.parent / path_part).resolve()
    except Exception as e:
        errors.append((line_idx, clean_url, f"Failed to resolve link path: {e}"))
        return errors

    # 5. Target must stay within repository root boundary
    if not target_path.is_relative_to(resolved_root):
        errors.append((line_idx, clean_url, f"Link targets path outside repository boundary: {path_part}"))
        return errors

    # 6. Verify target exists
    if not target_path.exists():
        errors.append((line_idx, clean_url, f"Relative link targets non-existent path: {path_part}"))

    return errors


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

    # Pass 1: Parse code blocks & collect reference definitions
    ref_defs: Dict[str, Tuple[int, str]] = {}
    non_code_lines: List[Tuple[int, str]] = []

    in_code_block = False
    current_fence_char = None
    current_fence_len = 0

    for line_idx, line in enumerate(lines, 1):
        stripped = line.strip()
        fence_match = re.match(r"^(`{3,}|~{3,})(.*)$", stripped)
        if fence_match:
            fence = fence_match.group(1)
            info = fence_match.group(2).strip()
            if not in_code_block:
                in_code_block = True
                current_fence_char = fence[0]
                current_fence_len = len(fence)
                continue
            elif fence[0] == current_fence_char and len(fence) >= current_fence_len and not info:
                in_code_block = False
                current_fence_char = None
                current_fence_len = 0
                continue

        if in_code_block:
            continue

        ref_match = REF_DEF_PATTERN.match(line)
        if ref_match:
            ref_label = ref_match.group(1).strip().lower()
            ref_url = ref_match.group(2).strip()
            ref_defs[ref_label] = (line_idx, ref_url)
            # Also validate the target defined in the reference
            errors.extend(validate_target_url(ref_url, file_path, repo_root, line_idx))
        else:
            non_code_lines.append((line_idx, line))

    # Pass 2: Check inline links and reference usages
    for line_idx, line in non_code_lines:
        # Strip inline code spans to avoid checking illustrative syntax examples
        line_clean = re.sub(r"`[^`]*`", "", line)

        # Check inline links: [text](url)
        for match in LINK_PATTERN.finditer(line_clean):
            raw_url = match.group(2).strip()
            errors.extend(validate_target_url(raw_url, file_path, repo_root, line_idx))

        # Check reference-style links: [text][ref] or [text][]
        for match in REF_LINK_PATTERN.finditer(line_clean):
            text_part = match.group(1).strip()
            ref_part = match.group(2).strip()
            ref_key = (ref_part if ref_part else text_part).lower()

            if ref_key not in ref_defs:
                errors.append((line_idx, f"[{text_part}][{ref_part}]", f"Unresolved reference link label: '{ref_key}'"))

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
