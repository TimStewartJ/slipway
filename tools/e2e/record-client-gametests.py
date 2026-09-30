"""Rewrites validation.json after the move to client GameTests.

Every Prism-harness entry is marked superseded (its original result kept) and points at the run that replaced it: the
next run of the same scenario, or for a scenario's last Prism run the client GameTest run of the series that retired
it. Client GameTest runs of a series and the packaged-jar check are added with their report paths, copied into
E:\\slipway-e2e\\runs\\<run id>. Usage: python tools/e2e/record-client-gametests.py <series dir> [<series dir> ...]
"""
import datetime
import json
import os
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RUNS = r"E:\slipway-e2e\runs"
VALIDATION = os.path.join(REPO, "validation.json")


def commit():
    return subprocess.run(["git", "-C", REPO, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()


def add_series(entries, series_dir):
    summary = json.load(open(os.path.join(series_dir, "summary.json"), encoding="utf-8"))
    if isinstance(summary, dict):
        summary = [summary]
    stamp = datetime.datetime.fromtimestamp(os.path.getmtime(os.path.join(series_dir, "summary.json"))).strftime("%Y%m%d-%H%M")
    added = {}
    for run in summary:
        n = run["Run"]
        results_file = os.path.join(series_dir, f"run{n}-results.json")
        junit_file = os.path.join(series_dir, f"run{n}-junit.xml")
        results = json.load(open(results_file, encoding="utf-8"))
        run_id = f"clientgametest-{stamp}-r{n}"
        folder = os.path.join(RUNS, run_id)
        os.makedirs(folder, exist_ok=True)
        for f in (results_file, junit_file, os.path.join(series_dir, f"run{n}.log")):
            if os.path.exists(f):
                shutil.copy2(f, folder)
        for s in results["scenarios"]:
            entry_id = f"{run_id}-{s['scenario']}"
            evidence = [os.path.join(folder, os.path.basename(results_file)), os.path.join(folder, os.path.basename(junit_file))]
            shots = os.path.join(REPO, "build", "client-gametest", "screenshots", s["scenario"])
            if n == len(summary) and os.path.isdir(shots):
                target = os.path.join(folder, "screenshots", s["scenario"])
                shutil.copytree(shots, target, dirs_exist_ok=True)
                evidence += [os.path.join(target, f) for f in sorted(os.listdir(target))]
            entries.append({
                "scenario": s["scenario"],
                "result": "pass" if s["status"] == "passed" else "fail",
                "runId": entry_id,
                "harness": "client-gametest",
                "series": os.path.basename(series_dir),
                "date": datetime.datetime.fromtimestamp(os.path.getmtime(results_file)).isoformat(timespec="seconds"),
                "commit": run.get("Commit") or commit(),
                "seconds": s["seconds"],
                "report": os.path.join(folder, os.path.basename(junit_file)),
                "results": os.path.join(folder, os.path.basename(results_file)),
                "evidence": evidence,
                "checks": [f"{k} = {v}" for k, v in s.get("metrics", {}).items()],
                "message": s.get("message"),
            })
            added[s["scenario"]] = entry_id
    return added


def main():
    data = json.load(open(VALIDATION, encoding="utf-8"))
    entries = data["runs"]
    prism = [e for e in entries if e.get("harness", "prism") == "prism"]
    for e in prism:
        e.setdefault("harness", "prism")
    replaced_by = {}
    for series_dir in sys.argv[1:]:
        replaced_by = add_series(entries, series_dir)
    # Prism runs: superseded by the next run of the scenario, the last by the client GameTest that retired it.
    by_scenario = {}
    for e in sorted(prism, key=lambda e: e["date"]):
        by_scenario.setdefault(e["scenario"], []).append(e)
    for scenario, runs in by_scenario.items():
        for i, e in enumerate(runs):
            if e.get("result") == "superseded":
                continue
            e["originalResult"] = e["result"]
            e["result"] = "superseded"
            e["supersededBy"] = runs[i + 1]["runId"] if i + 1 < len(runs) else replaced_by.get(scenario)
            if e.get("originalResult") == "pending-review":
                e["visualReview"] = "never reviewed; replaced by the client GameTests' structured and reference-image checks"
    packaged = os.path.join(REPO, "build", "packaged-check", "report.json")
    if os.path.exists(packaged):
        report = json.load(open(packaged, encoding="utf-8"))
        stamp = datetime.datetime.fromtimestamp(os.path.getmtime(packaged)).strftime("%Y%m%d-%H%M%S")
        run_id = f"packaged-jar-check-{stamp}"
        folder = os.path.join(RUNS, run_id)
        os.makedirs(folder, exist_ok=True)
        shutil.copy2(packaged, folder)
        if not any(e["runId"] == run_id for e in entries):
            entries.append({
                "scenario": "packaged-jar-check",
                "result": "pass" if report.get("result") == "pass" else "fail",
                "runId": run_id,
                "harness": "production-run",
                "date": datetime.datetime.fromtimestamp(os.path.getmtime(packaged)).isoformat(timespec="seconds"),
                "commit": commit(),
                "report": os.path.join(folder, "report.json"),
                "evidence": [os.path.join(folder, "report.json")],
                "checks": [f"mods = {report.get('mods')}", f"mixinOrLoaderErrors = {report.get('mixinOrLoaderErrors')}", f"vesselAssembled = {report.get('vesselAssembled')}"],
            })
    data["schemaVersion"] = 2
    data["note"] = ("Since 2026-09-30 in-game validation runs as Fabric client GameTests (harness client-gametest) and a packaged-jar "
                    "check (harness production-run). Prism-harness runs (harness prism) are all superseded: originalResult keeps their "
                    "outcome and supersededBy names the run that replaced them.")
    pending = [e["runId"] for e in entries if e.get("result") == "pending-review"]
    if pending:
        raise SystemExit(f"entries still pending review: {pending}")
    json.dump(data, open(VALIDATION, "w", encoding="utf-8"), indent=1)
    print(f"{len(entries)} entries; {sum(1 for e in entries if e.get('harness') == 'client-gametest')} client GameTest entries")


if __name__ == "__main__":
    main()
