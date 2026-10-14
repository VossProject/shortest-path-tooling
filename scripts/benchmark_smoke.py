#!/usr/bin/env python3
"""Run a light benchmark through benchmarkCanonical and check its output the way
shortest-path-benchmarks reads it.

The benchmarks harness (shortest-path-benchmarks) calls this repository's
benchmark adapter from projects/shortest-path/run.sh:

    ./gradlew -PshortestPathDir=PLUGIN benchmarkCanonical --args="--manifest M --output OUT"

with BENCHMARK_RUN_ID set, and validates the protocol-v1 envelope it writes
(scripts/run-suite, scripts/lib/benchlib.py validate_observation,
schemas/observation-v2.json). This script does the same for two cases on each
backend, so CI notices when the adapter stops working for the harness.

If you change the adapter's arguments, manifest format or output, update
shortest-path-benchmarks to match (run.sh, benchlib.py, the schemas) and this
script with it.

Usage: python3 scripts/benchmark_smoke.py   (writes build/benchmark-smoke/)
"""

import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# One reachable and one curated-unreachable case (gps-natural-0012's negativeProfiles is [early]).
CASES = [("gps-natural-0012", "maxed", True), ("gps-natural-0012", "early", False)]
CLOCK = 100_000_000
PROJECTS = {
    "shortest-path": {"algorithm": "legacy", "diagnostic": False},
    "shortest-path-exact": {"algorithm": "exact", "diagnostic": True, "exact_session": "target"},
}
# observation-v2's required fields, and the types the harness accepts for them.
REQUIRED = {
    "schema_version": int, "protocol_version": int, "run_id": str, "project": str,
    "implementation": str, "algorithm": str, "route_id": str, "route_name": str,
    "account_profile": str, "repetition": int, "reachable": (bool, type(None)),
    "path_cost": (int, type(None)), "expected_reachable": bool, "correctness": str,
    "total_ns": (int, type(None)),
}


class SmokeError(Exception):
    pass


def manifest(project):
    adapter_args = dict(PROJECTS[project])
    return {
        "format_version": 1,
        "project": project,
        "policy": {"repetitions": 1, "warmup": False, "synthetic_benchmark_time": CLOCK,
                   "adapter_args": adapter_args},
        "cases": [{"route_id": route, "profile": profile, "repetition": 0, "expected_reachable": reachable}
                  for route, profile, reachable in CASES],
    }


def check_envelope(project, envelope):
    """Raises SmokeError unless the envelope is one the harness accepts with every case as expected."""
    if envelope.get("protocol_version") != 1 or not isinstance(envelope.get("execution_metadata"), dict) \
            or not isinstance(envelope.get("observations"), list):
        raise SmokeError("not a protocol-v1 envelope")
    execution = envelope["execution_metadata"]
    for field in ("implementation", "algorithm"):
        if not isinstance(execution.get(field), str):
            raise SmokeError(f"execution_metadata.{field} must be a string")
    if execution["algorithm"] != PROJECTS[project]["algorithm"]:
        raise SmokeError(f"algorithm is {execution['algorithm']!r}")
    if execution.get("synthetic_benchmark_time") != CLOCK:
        raise SmokeError(f"synthetic_benchmark_time is {execution.get('synthetic_benchmark_time')!r}")
    rows = envelope["observations"]
    seen = sorted((row.get("route_id"), row.get("account_profile")) for row in rows)
    if seen != sorted((route, profile) for route, profile, _ in CASES):
        raise SmokeError(f"observations are for {seen}")
    for row in rows:
        label = f"{row['route_id']}/{row['account_profile']}"
        for field, kind in REQUIRED.items():
            # bool is an int in Python; the harness's schema keeps them apart.
            if field not in row or not isinstance(row[field], kind) \
                    or (kind is int and isinstance(row[field], bool)):
                raise SmokeError(f"{label}: {field} is missing or has the wrong type")
        if row["schema_version"] != 2 or row["protocol_version"] != 1 or row["project"] != project:
            raise SmokeError(f"{label}: wrong schema_version, protocol_version or project")
        if row["correctness"] != "expected":
            raise SmokeError(f"{label}: correctness is {row['correctness']!r} ({row.get('failure_reason')})")
        if row["reachable"] is not row["expected_reachable"]:
            raise SmokeError(f"{label}: reachable is {row['reachable']}")
        if (row["path_cost"] is None) == row["reachable"]:
            raise SmokeError(f"{label}: path_cost {row['path_cost']} disagrees with reachable")
        if row["total_ns"] is None:
            raise SmokeError(f"{label}: a measured observation needs total_ns")
        if PROJECTS[project]["diagnostic"] and not isinstance(row.get("graph_reused"), bool):
            raise SmokeError(f"{label}: diagnostic exact observation has no graph_reused")


def run(project, output_dir):
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / f"{project}-manifest.json"
    output = output_dir / f"{project}.json"
    manifest_path.write_text(json.dumps(manifest(project)))
    env = dict(os.environ, BENCHMARK_RUN_ID=f"ci-smoke-{project}")
    subprocess.run(["./gradlew", "--quiet", "benchmarkCanonical",
                    f"--args=--manifest {manifest_path} --output {output}"],
                   cwd=ROOT, env=env, check=True)
    return json.loads(output.read_text())


def main():
    output_dir = ROOT / "build/benchmark-smoke"
    failures = 0
    for project in PROJECTS:
        try:
            check_envelope(project, run(project, output_dir))
            print(f"{project}: ok")
        except (SmokeError, subprocess.CalledProcessError, ValueError) as error:
            print(f"{project}: FAILED: {error}", file=sys.stderr)
            failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
