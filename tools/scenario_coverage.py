#!/usr/bin/env python3
"""Report which regression scenarios have a passing automated test.

    python tools/scenario_coverage.py [--results DIR] [--write] [--min PERCENT]

Joins three things:
  docs/scenarios.toml     the scenarios the project commits to
  @Covers("ID") in tests  which test claims which scenario
  JUnit XML results       whether that test ran and passed

A scenario counts as covered only if at least one test that claims it passed
in the given results. Without --results, a claim alone counts, which is
useful while writing tests but is not what CI reports.

Exits non-zero when a test claims an id that is not in the catalogue, when a
claimed test failed, or when coverage is below --min.
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COVERS = re.compile(r'@Covers\("([A-Z0-9-]+)"\)')
FUNCTION = re.compile(r"\bfun\s+(?:`([^`]+)`|(\w+))\s*\(")


def load_catalogue() -> list[dict]:
    with open(os.path.join(ROOT, "docs", "scenarios.toml"), "rb") as handle:
        return tomllib.load(handle)["scenario"]


def find_claims() -> dict[str, list[tuple[str, str]]]:
    """scenario id -> [(test function name, file relative to the repo)]"""
    claims: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for path in glob.glob(os.path.join(ROOT, "**", "src", "**", "*.kt"), recursive=True):
        if os.sep + "build" + os.sep in path:
            continue
        pending: list[str] = []
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                pending += COVERS.findall(line)
                match = FUNCTION.search(line)
                if match and pending:
                    name = match.group(1) or match.group(2)
                    for scenario in pending:
                        claims[scenario].append((name, os.path.relpath(path, ROOT).replace(os.sep, "/")))
                    pending = []
    return claims


def load_results(directory: str) -> dict[str, bool]:
    """test function name -> passed. A name that failed anywhere is recorded as failed."""
    results: dict[str, bool] = {}
    pattern = os.path.join(directory, "**", "test-results", "**", "*.xml")
    for path in glob.glob(pattern, recursive=True) + glob.glob(
        os.path.join(directory, "**", "androidTest-results", "**", "*.xml"), recursive=True
    ):
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter("testcase"):
            name = case.get("name", "")
            # Parameterised and device runs can append "[...]" to the method name.
            name = re.sub(r"\[.*\]$", "", name).strip()
            failed = case.find("failure") is not None or case.find("error") is not None
            skipped = case.find("skipped") is not None
            if skipped:
                continue
            results[name] = results.get(name, True) and not failed
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", help="directory containing JUnit XML results")
    parser.add_argument("--write", action="store_true", help="write docs/regression-matrix.md")
    parser.add_argument("--min", type=float, default=0.0, help="fail below this coverage percentage")
    args = parser.parse_args()

    catalogue = load_catalogue()
    known = {scenario["id"] for scenario in catalogue}
    claims = find_claims()
    results = load_results(args.results) if args.results else None

    problems: list[str] = []
    for scenario_id in sorted(set(claims) - known):
        where = ", ".join(sorted({file for _, file in claims[scenario_id]}))
        problems.append(f"@Covers(\"{scenario_id}\") is not in docs/scenarios.toml ({where})")

    rows = []
    covered = 0
    for scenario in catalogue:
        scenario_id = scenario["id"]
        tests = claims.get(scenario_id, [])
        if results is None:
            passing = tests
        else:
            passing = [test for test in tests if results.get(test[0]) is True]
            for name, file in tests:
                if results.get(name) is False:
                    problems.append(f"{scenario_id}: test '{name}' in {file} failed")
        manual = scenario.get("manual")
        if manual and tests:
            problems.append(f"{scenario_id} is marked manual but has tests; remove the 'manual' key")
        if passing:
            covered += 1
            status = f"{len(passing)} test{'s' if len(passing) != 1 else ''}"
        elif manual:
            status = "manual"
        else:
            status = "NOT COVERED"
            problems.append(f"{scenario_id} has no passing test and is not marked manual")
        rows.append((scenario_id, scenario["area"], scenario["title"], status, manual or ""))

    total = len(catalogue)
    percent = covered / total * 100 if total else 0.0
    basis = "tests that passed in the given results" if results is not None else "claims only, results not checked"

    lines = [
        "# Regression matrix",
        "",
        f"**{covered} of {total} scenarios have a passing automated test ({percent:.1f}%).** Counted from {basis}.",
        "",
        "Generated by `tools/scenario_coverage.py` from `docs/scenarios.toml`, the `@Covers` annotations in the tests,",
        "and the JUnit results. This is coverage of defined behaviours, not of lines of code.",
        "",
        "| Id | Area | Scenario | Automated |",
        "|---|---|---|---|",
    ]
    for scenario_id, area, title, status, _ in rows:
        lines.append(f"| {scenario_id} | {area} | {title} | {status} |")
    manual_rows = [row for row in rows if row[3] == "manual"]
    if manual_rows:
        lines += ["", "## Checked by hand, and why", ""]
        for scenario_id, _, title, _, reason in manual_rows:
            lines.append(f"- **{scenario_id}** {title}. {reason}")
    report = "\n".join(lines) + "\n"

    if args.write:
        with open(os.path.join(ROOT, "docs", "regression-matrix.md"), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(report)
    print(report)

    for problem in problems:
        print(f"PROBLEM: {problem}", file=sys.stderr)
    if percent < args.min:
        print(f"PROBLEM: coverage {percent:.1f}% is below the required {args.min:.1f}%", file=sys.stderr)
        return 1
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
