#!/usr/bin/env python3
"""Turn Playwright's JSON report into a GitHub job summary (--summary) or annotations (--annotate).

Like report.py, this exists so that a failing browser test can be read on the public run page without signing in
or downloading the HTML report.
"""
import json
import os
import re
import sys

ANSI = re.compile(r"\x1b\[[0-9;]*m")


def esc(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def walk(suite, trail=()):
    here = trail + ((suite.get("title"),) if suite.get("title") else ())
    for spec in suite.get("specs", []):
        for test in spec.get("tests", []):
            yield here, spec, test
    for child in suite.get("suites", []):
        yield from walk(child, here)


def collect(path):
    data = json.load(open(path, encoding="utf-8"))
    rows = []
    for top in data.get("suites", []):
        for trail, spec, test in walk(top):
            results = test.get("results", [])
            last = results[-1] if results else {}
            message = ANSI.sub("", (last.get("error") or {}).get("message", "")).strip()
            rows.append({
                "file": spec.get("file", ""), "line": spec.get("line", 1),
                "title": " > ".join([t for t in trail[1:]] + [spec.get("title", "")]),
                "project": test.get("projectName", ""),
                "status": test.get("status", "unknown"),  # expected | unexpected | flaky | skipped
                "message": message,
            })
    return data, rows


def summary(path):
    if not os.path.exists(path):
        print("### Browser tests\n\nNo report was written: the tests did not start (see the step log).")
        return
    data, rows = collect(path)
    count = lambda s: sum(1 for r in rows if r["status"] == s)
    passed, failed, flaky, skipped = count("expected"), count("unexpected"), count("flaky"), count("skipped")
    print("### Browser tests (Playwright, Chromium, against the real payguard in Docker)\n")
    print(f"| Passed | Failed | Flaky | Skipped |\n|---:|---:|---:|---:|\n| {passed} | {failed} | {flaky} | {skipped} |\n")
    bad = [r for r in rows if r["status"] in ("unexpected", "flaky")]
    for r in bad:
        print(f"**{r['status'].upper()}** `{r['project']}` {r['file']}:{r['line']} — {r['title']}\n")
        if r["message"]:
            print("```\n" + "\n".join(r["message"].splitlines()[:12]) + "\n```\n")
    if not bad:
        print("Every browser test passed.")


def annotate(path):
    if not os.path.exists(path):
        print("::error title=browser tests::no report was written; the tests did not start")
        return
    _, rows = collect(path)
    for r in rows:
        if r["status"] in ("unexpected", "flaky"):
            first = " | ".join(r["message"].splitlines()[:6])
            print(f"::error file=ui-tests/e2e/{r['file']},line={r['line']},title={esc(r['status'])} "
                  f"[{esc(r['project'])}]::{esc(r['title'] + ' — ' + first)}")


if __name__ == "__main__":
    mode, report = (sys.argv[1], sys.argv[2]) if len(sys.argv) == 3 else ("--summary", "ui-tests/ui-report.json")
    {"--summary": summary, "--annotate": annotate}[mode](report)
