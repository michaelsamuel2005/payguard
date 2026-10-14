#!/usr/bin/env python3
"""Turn a Maven build log and Surefire reports into GitHub annotations (--annotate) or a job summary (--summary).

Annotations and summaries are visible on the public run page without signing in, so a failure can be read and
fixed without downloading logs.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

COMPILE = re.compile(r"^\[ERROR\] (?P<file>/\S+\.java):\[(?P<line>\d+),(?P<col>\d+)\] (?P<msg>.*)$")


def esc(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def compile_errors(log_path):
    if not os.path.exists(log_path):
        return []
    lines = open(log_path, encoding="utf-8", errors="replace").read().splitlines()
    out, seen = [], set()
    for i, line in enumerate(lines):
        m = COMPILE.match(line)
        if not m:
            continue
        detail = [m["msg"]] + [l.strip() for l in lines[i + 1:i + 4] if l.startswith("  ") and ":" in l]
        rel = relative(m["file"])
        key = (rel, m["line"], m["msg"])
        if key not in seen:
            seen.add(key)
            out.append((rel, m["line"], m["col"], " | ".join(detail)))
    return out


def relative(path: str) -> str:
    workspace = os.environ.get("GITHUB_WORKSPACE", "")
    if workspace and path.startswith(workspace + "/"):
        return path[len(workspace) + 1:]
    i = path.find("/src/")
    return path[i + 1:] if i >= 0 else path


def root_cause(text: str) -> str:
    causes = [l.strip() for l in (text or "").splitlines() if l.strip().startswith("Caused by:")]
    return causes[-1] if causes else ""


def test_results(report_dir):
    suites, failures = [], []
    for path in sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml"))):
        suite = ET.parse(path).getroot()
        suites.append((suite.get("name"), int(suite.get("tests", 0)), int(suite.get("failures", 0)),
                       int(suite.get("errors", 0)), int(suite.get("skipped", 0))))
        for case in suite.iter("testcase"):
            for kind in ("failure", "error"):
                for problem in case.findall(kind):
                    message = (problem.get("message") or "").strip()
                    cause = root_cause(problem.text)
                    failures.append((case.get("classname"), case.get("name"), kind, message[:600], cause[:600]))
    return suites, failures


def main():
    mode, log_path, report_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    errors = compile_errors(log_path)
    suites, failures = test_results(report_dir)
    if mode == "--annotate":
        for rel, line, col, msg in errors[:40]:
            print(f"::error file={rel},line={line},col={col},title=compile error::{esc(msg)}")
        for cls, name, kind, message, cause in failures[:40]:
            text = message + ("\n" + cause if cause and cause not in message else "")
            print(f"::error title=test {kind}: {cls.rsplit('.', 1)[-1]}.{name}::{esc(text)}")
        if not errors and not failures and os.path.exists(log_path):
            tail = [l for l in open(log_path, encoding="utf-8", errors="replace").read().splitlines()
                    if l.startswith("[ERROR]")][:15]
            for l in tail:
                print(f"::error title=build error::{esc(l)}")
        return
    total = [sum(s[i] for s in suites) for i in range(1, 5)]
    print("## payguard build\n")
    if errors:
        print(f"**{len(errors)} compile error(s)**\n")
        for rel, line, col, msg in errors[:40]:
            print(f"- `{rel}:{line}:{col}` {msg}")
        print()
    print(f"**Tests:** {total[0]} run, {total[1]} failed, {total[2]} errors, {total[3]} skipped\n")
    if suites:
        print("| test class | tests | failed | errors | skipped |\n|---|---:|---:|---:|---:|")
        for name, t, f, e, s in suites:
            print(f"| {name.rsplit('.', 1)[-1]} | {t} | {f} | {e} | {s} |")
        print()
    for cls, name, kind, message, cause in failures[:40]:
        print(f"- **{cls.rsplit('.', 1)[-1]}.{name}** ({kind}): {message}" + (f"  \n  {cause}" if cause else ""))


if __name__ == "__main__":
    main()
