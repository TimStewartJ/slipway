"""Rewrites validation.json after the move to client GameTests.

Usage: python tools/e2e/record-client-gametests.py [--superseded <dir>] [<dir>] [--replacing <dir>] ...

Series directories are given oldest first. A series directory holds runN-results.json, runN-junit.xml, runN.log,
optionally runN-screenshots/<scenario>/ and a summary.json with each run's commit (without one, the commit is the last
one before the run started). Every client GameTest run is added with its report paths, copied into
E:\\slipway-e2e\\runs\\<run id>. A --superseded series (an earlier series that later ones replaced) has its runs marked
superseded by the first run of the same scenario in the next series that is not itself superseded. A --replacing
series (a scenario that changed after an earlier series, rerun on its own) also supersedes the earlier series' runs
of its scenarios. Plain series stay current. Every Prism-harness entry is marked superseded too (its outcome kept in
originalResult) and names the run that replaced it: the next Prism run of the same scenario, or for a scenario's last
Prism run the first current client GameTest run. The packaged-jar check report in build/packaged-check is added as
well. Entries that are already present (same run id) are left alone, so the script can be rerun.
"""
import datetime
import json
import os
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RUNS = os.environ.get("SLIPWAY_RUNS", r"E:\slipway-e2e\runs")
VALIDATION = os.environ.get("SLIPWAY_VALIDATION", os.path.join(REPO, "validation.json"))


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True).stdout.strip()


def started(results):
    """The run's start as a local datetime (results.json stores UTC)."""
    utc = datetime.datetime.fromisoformat(results["startedAt"][:26].rstrip("Z") + "+00:00")
    return utc.astimezone().replace(tzinfo=None)


def load_runs(series_dir):
    """The series' runs in order: (number, results, commit, note)."""
    commits, notes = {}, {}
    summary_file = os.path.join(series_dir, "summary.json")
    if os.path.exists(summary_file):
        summary = json.load(open(summary_file, encoding="utf-8-sig"))
        for run in summary if isinstance(summary, list) else [summary]:
            commits[run["Run"]] = run.get("Commit")
            notes[run["Run"]] = run.get("Note")
    runs = []
    n = 1
    while os.path.exists(os.path.join(series_dir, f"run{n}-results.json")):
        results = json.load(open(os.path.join(series_dir, f"run{n}-results.json"), encoding="utf-8-sig"))
        commit = commits.get(n) or git("log", "-1", "--format=%h", "--before=" + results["startedAt"][:19] + " +0000")
        runs.append((n, results, commit, notes.get(n)))
        n += 1
    return runs


def add_series(entries, series_dir):
    """Adds the series' runs that are not recorded yet; returns {scenario: [entry ids of the series, oldest first]}."""
    existing = {e["runId"] for e in entries}
    ids = {}
    for n, results, commit, note in load_runs(series_dir):
        when = started(results)
        run_id = "clientgametest-" + when.strftime("%Y%m%d-%H%M%S")
        folder = os.path.join(RUNS, run_id)
        os.makedirs(folder, exist_ok=True)
        copied = {}
        for kind, name in (("results", f"run{n}-results.json"), ("junit", f"run{n}-junit.xml"), ("log", f"run{n}.log")):
            source = os.path.join(series_dir, name)
            if os.path.exists(source):
                shutil.copy2(source, folder)
                copied[kind] = os.path.join(folder, name)
        for s in results["scenarios"]:
            entry_id = f"{run_id}-{s['scenario']}"
            ids.setdefault(s["scenario"], []).append(entry_id)
            if entry_id in existing:
                continue
            evidence = [copied[k] for k in ("results", "junit", "log") if k in copied]
            shots = os.path.join(series_dir, f"run{n}-screenshots", s["scenario"])
            if os.path.isdir(shots):
                target = os.path.join(folder, "screenshots", s["scenario"])
                shutil.copytree(shots, target, dirs_exist_ok=True)
                evidence += [os.path.join(target, f) for f in sorted(os.listdir(target))]
            entry = {
                "scenario": s["scenario"],
                "result": "pass" if s["status"] == "passed" else "fail",
                "runId": entry_id,
                "harness": "client-gametest",
                "series": os.path.basename(os.path.normpath(series_dir)),
                "run": n,
                "date": when.isoformat(timespec="seconds"),
                "commit": commit,
                "seconds": s["seconds"],
                "report": copied.get("junit"),
                "results": copied.get("results"),
                "evidence": evidence,
                "checks": [f"{k} = {v}" for k, v in s.get("metrics", {}).items()],
            }
            if s.get("message"):
                entry["message"] = s["message"]
            if note:
                entry["note"] = note
            entries.append(entry)
    return ids


def main():
    args = sys.argv[1:]
    specs = []
    while args:
        if args[0] in ("--superseded", "--replacing"):
            specs.append((args[1], args[0][2:]))
            args = args[2:]
        else:
            specs.append((args[0], "current"))
            args = args[1:]
    if all(mode == "superseded" for _, mode in specs):
        raise SystemExit(__doc__)
    data = json.load(open(VALIDATION, encoding="utf-8"))
    entries = data["runs"]
    prism = [e for e in entries if e.get("harness", "prism") == "prism"]
    for e in prism:
        e.setdefault("harness", "prism")
    series = [(mode, add_series(entries, series_dir)) for series_dir, mode in specs]
    by_id = {e["runId"]: e for e in entries}
    for i, (mode, ids) in enumerate(series):
        for scenario, entry_ids in ids.items():
            replacement = next((later[scenario][0] for later_mode, later in series[i + 1:] if scenario in later
                                and (later_mode == "replacing" or (mode == "superseded" and later_mode != "superseded"))), None)
            if replacement is None:
                continue
            for entry_id in entry_ids:
                e = by_id[entry_id]
                if e["result"] != "superseded":
                    e["originalResult"] = e["result"]
                    e["result"] = "superseded"
                    e["supersededBy"] = replacement
    replaced_by = {}
    for mode, ids in series:
        if mode != "superseded":
            for scenario, entry_ids in ids.items():
                replaced_by.setdefault(scenario, entry_ids[0])
    # Prism runs: superseded by the next run of the scenario, the last by the client GameTest run that retired it.
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
            if e["originalResult"] == "pending-review":
                e["visualReview"] = "never reviewed; replaced by the client GameTests' structured and reference-image checks"
    packaged = os.path.join(REPO, "build", "packaged-check", "report.json")
    if os.path.exists(packaged):
        report = json.load(open(packaged, encoding="utf-8"))
        when = datetime.datetime.fromtimestamp(os.path.getmtime(packaged))
        run_id = "packaged-jar-check-" + when.strftime("%Y%m%d-%H%M%S")
        if not any(e["runId"] == run_id for e in entries):
            folder = os.path.join(RUNS, run_id)
            os.makedirs(folder, exist_ok=True)
            shutil.copy2(packaged, folder)
            entries.append({
                "scenario": "packaged-jar-check",
                "result": "pass" if report.get("result") == "pass" else "fail",
                "runId": run_id,
                "harness": "production-run",
                "date": when.isoformat(timespec="seconds"),
                "commit": git("rev-parse", "--short", "HEAD"),
                "report": os.path.join(folder, "report.json"),
                "evidence": [os.path.join(folder, "report.json")],
                "checks": [f"{k} = {v}" for k, v in report.items() if k != "result"],
            })
    missing = sorted({e["runId"] for e in entries if e.get("result") == "superseded" and not e.get("supersededBy")})
    if missing:
        raise SystemExit(f"superseded entries without a replacing run: {missing}")
    pending = [e["runId"] for e in entries if e.get("result") == "pending-review"]
    if pending:
        raise SystemExit(f"entries still pending review: {pending}")
    data["schemaVersion"] = 2
    data["note"] = ("Since 2026-09-30 in-game validation runs as Fabric client GameTests (harness client-gametest) and a packaged-jar "
                    "check (harness production-run). Prism-harness runs (harness prism) and earlier client GameTest series are "
                    "superseded: originalResult keeps their outcome and supersededBy names the run that replaced them.")
    with open(VALIDATION, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
        f.write("\n")
    counts = {}
    for e in entries:
        key = (e.get("harness"), e["result"])
        counts[key] = counts.get(key, 0) + 1
    print(f"{len(entries)} entries: " + ", ".join(f"{h} {r}={c}" for (h, r), c in sorted(counts.items())))


if __name__ == "__main__":
    main()
