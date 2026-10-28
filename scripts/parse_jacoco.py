#!/usr/bin/env python3
"""
parse_jacoco.py - Parse JaCoCo CSV coverage reports and enforce quality thresholds.

Usage:
    ./scripts/parse_jacoco.py
    ./scripts/parse_jacoco.py --threshold 95.0
    ./scripts/parse_jacoco.py --fail-under-line 95.0 --fail-under-branch 90.0
    ./scripts/parse_jacoco.py --fail-under-gates
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
        description="Parse and audit JaCoCo CSV coverage metrics against quality gates."
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
        "--fail-under-line",
        type=float,
        default=None,
        help="Exit with non-zero exit code if overall line coverage is below this threshold (target: >= 95.0%%)",
    )
    parser.add_argument(
        "--fail-under-branch",
        type=float,
        default=None,
        help="Exit with non-zero exit code if overall branch coverage is below this threshold (target: >= 90.0%%)",
    )
    parser.add_argument(
        "--fail-under-condition",
        type=float,
        default=None,
        help="Alias for --fail-under-branch (target: >= 90.0%%)",
    )
    parser.add_argument(
        "--fail-under-gates",
        action="store_true",
        help="Enforce both standard quality gates: Line coverage >= 95.0%% and Condition/Branch coverage >= 90.0%%",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Print coverage details for all classes regardless of threshold",
    )

    args = parser.parse_args()

    # Handle composite quality gates flag and alias
    if args.fail_under_condition is not None and args.fail_under_branch is None:
        args.fail_under_branch = args.fail_under_condition

    if args.fail_under_gates:
        if args.fail_under_line is None:
            args.fail_under_line = 95.0
        if args.fail_under_branch is None:
            args.fail_under_branch = 90.0

    rows = parse_csv(args.file)
    if not rows:
        print("Warning: JaCoCo report is empty.", file=sys.stderr)
        if any(g is not None for g in [args.fail_under, args.fail_under_line, args.fail_under_branch]):
            print("Error: Coverage is 0.00% (report empty), failing quality gates.", file=sys.stderr)
            sys.exit(1)
        sys.exit(0)

    metrics = calculate_metrics(rows)
    cov_inst, tot_inst = metrics["INSTRUCTION"]
    cov_line, tot_line = metrics["LINE"]
    cov_branch, tot_branch = metrics["BRANCH"]

    if tot_inst == 0:
        print("Warning: JaCoCo report contains 0 measured instructions.", file=sys.stderr)
        if any(g is not None for g in [args.fail_under, args.fail_under_line, args.fail_under_branch]):
            print("Error: Coverage is 0.00% (0 total instructions), failing quality gates.", file=sys.stderr)
            sys.exit(1)
        sys.exit(0)

    print("=" * 80)
    print("                        JACOCO CODE COVERAGE SUMMARY")
    print("=" * 80)
    print(f"{'METRIC':<16} {'COVERED':<12} {'TOTAL':<12} {'COVERAGE':<12} {'GATE STATUS':<24}")
    print("-" * 80)
    for name, (cov, tot) in metrics.items():
        pct_val = (cov / tot * 100) if tot > 0 else 0.0
        pct_str = format_pct(cov, tot)
        gate_status = "-"
        if name == "LINE" and args.fail_under_line is not None:
            passed = pct_val >= args.fail_under_line
            gate_status = f">= {args.fail_under_line:.1f}% [{'PASSED' if passed else 'FAILED'}]"
        elif name == "BRANCH" and args.fail_under_branch is not None:
            passed = pct_val >= args.fail_under_branch
            gate_status = f">= {args.fail_under_branch:.1f}% [{'PASSED' if passed else 'FAILED'}]"
        elif name == "INSTRUCTION" and args.fail_under is not None:
            passed = pct_val >= args.fail_under
            gate_status = f">= {args.fail_under:.1f}% [{'PASSED' if passed else 'FAILED'}]"
        print(f"{name:<16} {cov:<12} {tot:<12} {pct_str:<12} {gate_status:<24}")
    print("=" * 80)

    overall_inst_pct = (cov_inst / tot_inst * 100) if tot_inst > 0 else 0.0
    overall_line_pct = (cov_line / tot_line * 100) if tot_line > 0 else 0.0
    overall_branch_pct = (cov_branch / tot_branch * 100) if tot_branch > 0 else 0.0

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
        header_title = "ALL CLASSES" if args.verbose else f"CLASSES BELOW {args.threshold:.1f}% INSTRUCTION THRESHOLD"
        print(f"\n{header_title}:")
        print("-" * 85)
        print(f"{'CLASS':<52} {'COVERAGE':<10} {'COVERED/TOTAL':<14} {'MISSED':<8}")
        print("-" * 85)
        for name, pct, c, tot, m in sorted(flagged_classes, key=lambda x: x[1]):
            print(f"{name:<52} {pct:>6.1f}%    {f'{c}/{tot}':<14} {m:<8}")
        print("-" * 85)
    else:
        print(f"\nAll classes satisfy the {args.threshold:.1f}% instruction coverage threshold!")

    branch_deficit_classes = []
    for r in rows:
        pkg = r.get("PACKAGE", "")
        cls = r.get("CLASS", "")
        full_name = f"{pkg}.{cls}" if pkg else cls

        bc = int(r.get("BRANCH_COVERED", 0))
        bm = int(r.get("BRANCH_MISSED", 0))
        btot = bc + bm
        bpct = (bc / btot * 100) if btot > 0 else 100.0

        target_branch = args.fail_under_branch if args.fail_under_branch is not None else 90.0
        if btot > 0 and (args.verbose or bpct < target_branch):
            branch_deficit_classes.append((full_name, bpct, bc, btot, bm))

    if branch_deficit_classes:
        target_branch = args.fail_under_branch if args.fail_under_branch is not None else 90.0
        print(f"\nCLASSES BELOW {target_branch:.1f}% CONDITION / BRANCH THRESHOLD:")
        print("-" * 85)
        print(f"{'CLASS':<52} {'COVERAGE':<10} {'COVERED/TOTAL':<14} {'MISSED':<8}")
        print("-" * 85)
        for name, pct, c, tot, m in sorted(branch_deficit_classes, key=lambda x: x[1]):
            print(f"{name:<52} {pct:>6.1f}%    {f'{c}/{tot}':<14} {m:<8}")
        print("-" * 85)

    gate_failures = []
    gate_passes = []

    if args.fail_under is not None:
        if overall_inst_pct < args.fail_under:
            gate_failures.append(f"Instruction coverage {overall_inst_pct:.2f}% < required threshold {args.fail_under:.2f}%")
        else:
            gate_passes.append(f"Instruction coverage {overall_inst_pct:.2f}% >= required threshold {args.fail_under:.2f}%")

    if args.fail_under_line is not None:
        if overall_line_pct < args.fail_under_line:
            gate_failures.append(f"Line coverage {overall_line_pct:.2f}% < required quality gate {args.fail_under_line:.2f}%")
        else:
            gate_passes.append(f"Line coverage {overall_line_pct:.2f}% >= required quality gate {args.fail_under_line:.2f}%")

    if args.fail_under_branch is not None:
        if overall_branch_pct < args.fail_under_branch:
            gate_failures.append(f"Condition/Branch coverage {overall_branch_pct:.2f}% < required quality gate {args.fail_under_branch:.2f}%")
        else:
            gate_passes.append(f"Condition/Branch coverage {overall_branch_pct:.2f}% >= required quality gate {args.fail_under_branch:.2f}%")

    if gate_passes:
        print("\nQUALITY GATE CHECKS PASSED:")
        for p in gate_passes:
            print(f"  ✓ {p}")

    if gate_failures:
        print("\nQUALITY GATE ENFORCEMENT FAILED:", file=sys.stderr)
        for f in gate_failures:
            print(f"  ✗ {f}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
