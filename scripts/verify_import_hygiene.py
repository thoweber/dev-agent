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
from typing import Dict, List, Set, Tuple

# Pattern matching any fully qualified Java class reference (package path + Capitalized Class Name)
# e.g., com.fasterxml.jackson.databind.ObjectMapper, jakarta.persistence.Entity, java.util.List
FQN_PATTERN = re.compile(
    r'\b([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+\.[A-Z][a-zA-Z0-9_]*(?:\.[A-Z][a-zA-Z0-9_]*)*)\b'
)

# Pattern to strip string and character literals
STRING_LITERAL_PATTERN = re.compile(r'"(?:\\.|[^"\\])*"')
CHAR_LITERAL_PATTERN = re.compile(r"'(?:\\.|[^'\\])'")

# Pattern to extract imported class names: import (static)? pkg.ClassName;
IMPORT_PATTERN = re.compile(r'^\s*import\s+(?:static\s+)?([a-zA-Z0-9_.]+)\s*;')


def sanitize_literals(line: str) -> str:
    """Replaces string and character literals with placeholders to prevent false comments or false FQN matches."""
    line = STRING_LITERAL_PATTERN.sub('""', line)
    line = CHAR_LITERAL_PATTERN.sub("''", line)
    return line


def check_file(file_path: str) -> List[Tuple[int, str, str]]:
    """
    Checks a single Java file for inline fully-qualified class names.
    Returns a list of (line_number, matched_fqn, line_content) tuples.
    """
    try:
        with open(file_path, "r", encoding="utf-8", errors="replace") as fh:
            raw_lines = fh.readlines()
    except Exception as e:
        return [(0, "", f"Could not read file {file_path}: {e}")]

    # Pass 1: Collect imported types and collect all candidate FQN occurrences
    imported_simple_names: Dict[str, str] = {}  # simple_name -> full_imported_fqn
    candidate_fqns_by_simple_name: Dict[str, Set[str]] = {}

    cleaned_lines: List[Tuple[int, str, str]] = []  # (line_num, sanitized_line, raw_line)
    in_block_comment = False

    for line_num, raw_line in enumerate(raw_lines, 1):
        line = raw_line.strip()

        # Handle text block opening/closing or block comments
        if in_block_comment:
            if "*/" in line:
                in_block_comment = False
                line = line[line.find("*/") + 2 :].strip()
            else:
                continue

        # Strip string literals before checking comment delimiters
        # This prevents "/*" or "//" inside string literals from corrupting comment state
        line = sanitize_literals(line)

        if "/*" in line:
            if "*/" in line:
                line = re.sub(r'/\*.*?\*/', '', line).strip()
            else:
                in_block_comment = True
                line = line[: line.find("/*")].strip()

        # Strip single-line comments
        if "//" in line:
            line = line[: line.find("//")].strip()

        # Check and skip import declarations
        if line.startswith("import "):
            import_match = IMPORT_PATTERN.match(raw_line)
            if import_match:
                full_path = import_match.group(1)
                parts = full_path.split(".")
                # If static import of a member, track the declaring class
                if "static " in raw_line and len(parts) >= 2 and parts[-1][0].islower():
                    simple = parts[-2]
                    imported_simple_names[simple] = ".".join(parts[:-1])
                elif parts:
                    simple = parts[-1]
                    imported_simple_names[simple] = full_path
            continue

        # Skip package declarations and empty lines
        if not line or line.startswith("package "):
            continue

        cleaned_lines.append((line_num, line, raw_line.rstrip()))

        # Collect candidate FQNs for collision detection
        for match in FQN_PATTERN.finditer(line):
            fqn = match.group(1)
            simple = fqn.split(".")[-1]
            candidate_fqns_by_simple_name.setdefault(simple, set()).add(fqn)

    # Pass 2: Identify genuine violations, exempting collisions
    violations = []
    for line_num, line, raw_line in cleaned_lines:
        for match in FQN_PATTERN.finditer(line):
            fqn = match.group(1)
            simple = fqn.split(".")[-1]

            # Collision Exception Check:
            # An inline FQN is permitted solely when disambiguating two classes that share
            # the exact same simple name within the same file:
            # 1. Simple name is imported from a different package:
            is_collision_with_import = (
                simple in imported_simple_names
                and imported_simple_names[simple] != fqn
            )
            # 2. File references two distinct packages for the same simple name:
            is_collision_in_file = len(candidate_fqns_by_simple_name.get(simple, set())) > 1

            if is_collision_with_import or is_collision_in_file:
                # Legitimate collision disambiguation exemption
                continue

            violations.append((line_num, fqn, raw_line))

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
