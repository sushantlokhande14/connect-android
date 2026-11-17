#!/usr/bin/env python3
"""Summarise JUnit XML results: totals, plus one line per failing test.

    python tools/test_summary.py [reports-dir]

The reports directory defaults to out/reports, which is where
tools/container-gradle.sh copies results after a containerised run.
Exits non-zero if any test failed.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    root_dir = sys.argv[1] if len(sys.argv) > 1 else "out/reports"
    files = glob.glob(os.path.join(root_dir, "**", "*.xml"), recursive=True)
    tests = failures = errors = skipped = 0
    per_module = {}
    for path in sorted(files):
        suite = ET.parse(path).getroot()
        count = int(suite.get("tests", 0))
        tests += count
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        module = os.path.relpath(path, root_dir).split(os.sep + "build" + os.sep)[0]
        per_module[module] = per_module.get(module, 0) + count
        for case in suite.findall("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                message = (bad.get("message") or bad.text or "").strip().replace("\n", " ")
                print(f"FAIL {suite.get('name', '').split('.')[-1]} | {case.get('name')} | {message[:500]}")
    for module, count in sorted(per_module.items()):
        print(f"{module}: {count}")
    print(f"tests={tests} failures={failures} errors={errors} skipped={skipped}")
    return 1 if failures or errors else 0


if __name__ == "__main__":
    sys.exit(main())
