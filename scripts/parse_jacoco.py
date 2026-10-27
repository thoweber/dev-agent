#!/usr/bin/env python3
"""
parse_jacoco.py - Parse JaCoCo CSV coverage reports and enforce quality thresholds.

Usage:
    ./scripts/parse_jacoco.py
    ./scripts/parse_jacoco.py --threshold 80.0
    ./scripts/parse_jacoco.py --file target/site/jacoco/jacoco.csv --fail-under 80.0
"""

import argparse
import csv
import sys
from pathlib import Path
from typing import Dict, List, Tuple


def parse_csv(csv_path: Path) -> List[Dict[str, str]]:
    if not csv_path.exists():
        print(f"Error: JaCoCo report file not found at: {csv_path}", file=sys.stderr)
        sys.exit(2)

    with csv_path.open(mode="r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        return list(reader)


def calculate_metrics(rows: List[Dict[str, str]]) -> Dict[str, Tuple[int, int]]:
    """Returns mapping of metric -> (covered, total)."""
    metrics = {
        "INSTRUCTION": (0, 0),
        "BRANCH": (0, 0),
        "LINE": (0, 0),
        "COMPLEXITY": (0, 0),
        "METHOD": (0, 0),
    }

    for row in rows:
        for m in metrics:
            missed = int(row.get(f"{m}_MISSED", 0))
            covered = int(row.get(f"{m}_COVERED", 0))
            curr_covered, curr_total = metrics[m]
            metrics[m] = (curr_covered + covered, curr_total + missed + covered)

    return metrics


def format_pct(covered: int, total: int) -> str:
    if total == 0:
        return "0.00%"
    return f"{(covered / total) * 100:.2f}%"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Parse and audit JaCoCo CSV coverage metrics."
    )
    parser.add_argument(
        "-f",
        "--file",
        type=Path,
        default=Path("target/site/jacoco/jacoco.csv"),
        help="Path to jacoco.csv report (default: target/site/jacoco/jacoco.csv)",
    )
    parser.add_argument(
        "-t",
        "--threshold",
        type=float,
        default=80.0,
        help="Flag individual classes with instruction coverage below this percentage (default: 80.0)",
    )
    parser.add_argument(
        "--fail-under",
        type=float,
        default=None,
        help="Exit with non-zero exit code if overall instruction coverage is below this threshold",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Print coverage details for all classes regardless of threshold",
    )

    args = parser.parse_args()

    rows = parse_csv(args.file)
    if not rows:
        print("Warning: JaCoCo report is empty.", file=sys.stderr)
        if args.fail_under is not None:
            print("Error: Coverage is 0.00% (report empty), failing threshold gate.", file=sys.stderr)
            sys.exit(1)
        sys.exit(0)

    metrics = calculate_metrics(rows)
    cov_inst, tot_inst = metrics["INSTRUCTION"]

    if tot_inst == 0:
        print("Warning: JaCoCo report contains 0 measured instructions.", file=sys.stderr)
        if args.fail_under is not None:
            print("Error: Coverage is 0.00% (0 total instructions), failing threshold gate.", file=sys.stderr)
            sys.exit(1)
        sys.exit(0)

    print("=" * 68)
    print("                 JACOCO CODE COVERAGE SUMMARY")
    print("=" * 68)
    print(f"{'METRIC':<16} {'COVERED':<12} {'TOTAL':<12} {'COVERAGE':<12}")
    print("-" * 68)
    for name, (cov, tot) in metrics.items():
        print(f"{name:<16} {cov:<12} {tot:<12} {format_pct(cov, tot):<12}")
    print("=" * 68)

    overall_inst_pct = (cov_inst / tot_inst * 100) if tot_inst > 0 else 0.0

    flagged_classes = []
    for r in rows:
        pkg = r.get("PACKAGE", "")
        cls = r.get("CLASS", "")
        full_name = f"{pkg}.{cls}" if pkg else cls

        c = int(r.get("INSTRUCTION_COVERED", 0))
        m = int(r.get("INSTRUCTION_MISSED", 0))
        tot = c + m
        pct = (c / tot * 100) if tot > 0 else 100.0

        if args.verbose or pct < args.threshold:
            flagged_classes.append((full_name, pct, c, tot, m))

    if flagged_classes:
        header_title = "ALL CLASSES" if args.verbose else f"CLASSES BELOW {args.threshold:.1f}% THRESHOLD"
        print(f"\n{header_title}:")
        print("-" * 85)
        print(f"{'CLASS':<52} {'COVERAGE':<10} {'COVERED/TOTAL':<14} {'MISSED':<8}")
        print("-" * 85)
        for name, pct, c, tot, m in sorted(flagged_classes, key=lambda x: x[1]):
            print(f"{name:<52} {pct:>6.1f}%    {f'{c}/{tot}':<14} {m:<8}")
        print("-" * 85)
    else:
        print(f"\nAll classes satisfy the {args.threshold:.1f}% instruction coverage threshold!")

    if args.fail_under is not None:
        if overall_inst_pct < args.fail_under:
            print(
                f"\nFAILED: Overall instruction coverage {overall_inst_pct:.2f}% is below required threshold {args.fail_under:.2f}%.",
                file=sys.stderr,
            )
            sys.exit(1)
        else:
            print(
                f"\nPASSED: Overall instruction coverage {overall_inst_pct:.2f}% satisfies required threshold {args.fail_under:.2f}%."
            )


if __name__ == "__main__":
    main()
