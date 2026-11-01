#!/usr/bin/env python3
"""
Verify Java Import Hygiene & Prohibition of Inline Fully Qualified Class Names.

Scans Java source files under src/ to ensure types are explicitly imported rather
than referenced with inline fully-qualified names (FQNs), enforcing Directive 20
and Section 6 of 02-coding-standards.md.
"""

import argparse
import os
import re
import sys
from typing import List, Tuple

# Pattern matching common fully qualified class references (package prefix + Capitalized Class Name)
FQN_PATTERN = re.compile(
    r'\b(?:java\.[a-z0-9_.]+|guru\.thomasweber\.[a-z0-9_.]+|org\.[a-z0-9_.]+|io\.[a-z0-9_.]+)\.[A-Z][a-zA-Z0-9_]*'
)

# Pattern to strip string literals
STRING_LITERAL_PATTERN = re.compile(r'"(?:\\.|[^"\\])*"')


def strip_string_literals(line: str) -> str:
    """Replaces string literals in the line with empty strings to prevent false positives."""
    return STRING_LITERAL_PATTERN.sub('""', line)


def check_file(file_path: str) -> List[Tuple[int, str, str]]:
    """
    Checks a single Java file for inline fully-qualified class names.
    Returns a list of (line_number, matched_fqn, line_content) tuples.
    """
    violations = []
    in_block_comment = False

    with open(file_path, "r", encoding="utf-8", errors="replace") as fh:
        for line_num, raw_line in enumerate(fh, 1):
            line = raw_line.strip()

            # Handle multi-line block comments
            if in_block_comment:
                if "*/" in line:
                    in_block_comment = False
                    line = line[line.find("*/") + 2 :].strip()
                else:
                    continue

            if "/*" in line:
                if "*/" in line:
                    # Single-line block comment
                    line = re.sub(r'/\*.*?\*/', '', line).strip()
                else:
                    in_block_comment = True
                    line = line[: line.find("/*")].strip()

            # Strip single-line comments
            if "//" in line:
                line = line[: line.find("//")].strip()

            # Skip package declarations, import statements, empty lines
            if not line or line.startswith("package ") or line.startswith("import "):
                continue

            # Strip string literals
            sanitized = strip_string_literals(line)

            match = FQN_PATTERN.search(sanitized)
            if match:
                violations.append((line_num, match.group(0), raw_line.rstrip()))

    return violations


def scan_directory(root_dir: str) -> List[Tuple[str, int, str, str]]:
    """
    Recursively scans the directory for Java files and collects FQN violations.
    Returns a list of (file_path, line_number, matched_fqn, line_content).
    """
    all_violations = []
    for root, _, files in os.walk(root_dir):
        for file in files:
            if file.endswith(".java"):
                file_path = os.path.join(root, file)
                for line_num, fqn, content in check_file(file_path):
                    all_violations.append((file_path, line_num, fqn, content))
    return all_violations


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Java import hygiene and forbid inline FQNs.")
    parser.add_argument(
        "--dir",
        default="src",
        help="Root directory to scan (defaults to 'src')",
    )
    args = parser.parse_args()

    if not os.path.isdir(args.dir):
        print(f"Error: Directory '{args.dir}' does not exist.")
        return 1

    violations = scan_directory(args.dir)

    if violations:
        print("=" * 80)
        print("JAVA IMPORT HYGIENE VIOLATIONS DETECTED (Directive 20):")
        print("Prefer explicit imports at top of file over inline fully qualified class names.")
        print("=" * 80)
        for path, line, fqn, content in violations:
            print(f"  {path}:{line} -> {fqn}")
            print(f"    Line: {content.strip()}")
        print("=" * 80)
        print(f"Total violations found: {len(violations)}")
        return 1

    print("=" * 80)
    print(f"JAVA IMPORT HYGIENE CHECK PASSED: 0 inline FQN violations found under '{args.dir}'.")
    print("=" * 80)
    return 0


if __name__ == "__main__":
    sys.exit(main())
