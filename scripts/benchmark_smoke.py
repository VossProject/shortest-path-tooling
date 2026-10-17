#!/usr/bin/env python3
"""Run a light benchmark through the benchmark task and check its output the way
collisionlab/osrs-benchmarks reads it.

osrs-benchmarks (src/osrs_benchmarks/tooling.py) runs

    gradle -PshortestPathDir=PLUGIN benchmark --args="--manifest M --output OUT"

with BENCHMARK_RUN_ID set, on a format-2 manifest (scenarios by suite and name), and reads the
fields checked here from the result. This script does the same for three scenarios on each
backend, so CI notices when the task stops working for it.

If you change the task's arguments, manifest format or output, update osrs-benchmarks to match
and this script with it.

Usage: python3 scripts/benchmark_smoke.py   (writes build/benchmark-smoke/)
"""

import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# A reachable and a curated-unreachable canonical case (gps-natural-0012's negativeProfiles is
# [early]), and a Java scenario with overrides.
CASES = [("canonical", "gps-natural-0012/maxed", True), ("canonical", "gps-natural-0012/early", False),
         ("routing-issues", "GE centre walk (routing control)", True)]
SETTINGS = {
    "legacy": {"algorithm": "legacy"},
    "astar": {"algorithm": "astar", "diagnostic": True, "astar_session": "target"},
}
# What osrs-benchmarks reads from each observation, and the types it accepts.
REQUIRED = {
    "run_id": str, "suite": str, "scenario": str, "category": str, "profile": str, "repetition": int,
    "expected_reachable": bool, "outcome": str, "check": str, "reachable": (bool, type(None)),
    "path_cost": (int, type(None)), "path_length": (int, type(None)), "total_ns": (int, type(None)),
    "allocated_bytes": (int, type(None)), "termination_reason": str, "failure": str,
}
# What it reads from execution_metadata's memory and world (A* only: the static routing data).
MEMORY = ("heap_after_prepare_bytes", "heap_after_run_bytes", "peak_heap_bytes", "run_gc_count", "run_gc_ms")
WORLD = ("transports", "transport_origins")
ASTAR_WORLD = ("search_tiles", "sites", "routing_components", "crossings")


class SmokeError(Exception):
    pass


def manifest(backend):
    return dict(SETTINGS[backend], format_version=2,
                warmup=[{"suite": CASES[0][0], "scenario": CASES[0][1]}],
                cases=[{"suite": suite, "scenario": name, "repetition": 0} for suite, name, _ in CASES])


def check_envelope(backend, envelope):
    """Raises SmokeError unless osrs-benchmarks would accept the result with every case as expected."""
    if envelope.get("format_version") != 2 or not isinstance(envelope.get("execution_metadata"), dict) \
            or not isinstance(envelope.get("observations"), list):
        raise SmokeError("not a format-2 result")
    execution = envelope["execution_metadata"]
    if execution.get("algorithm") != backend:
        raise SmokeError(f"algorithm is {execution.get('algorithm')!r}")
    for field in ("java_version", "vm_name", "vm_version"):
        if not isinstance(execution.get("runtime", {}).get(field), str):
            raise SmokeError(f"execution_metadata.runtime.{field} must be a string")
    for field in MEMORY:
        if not isinstance(execution.get("memory", {}).get(field), int):
            raise SmokeError(f"execution_metadata.memory.{field} must be an integer")
    for field in WORLD + (ASTAR_WORLD if backend == "astar" else ()):
        if not isinstance(execution.get("world", {}).get(field), int):
            raise SmokeError(f"execution_metadata.world.{field} must be an integer")
    rows = envelope["observations"]
    seen = sorted((row.get("suite"), row.get("scenario")) for row in rows)
    if seen != sorted((suite, name) for suite, name, _ in CASES):
        raise SmokeError(f"observations are for {seen}")
    for row in rows:
        label = f"{row['suite']}/{row['scenario']}"
        for field, kind in REQUIRED.items():
            # bool is an int in Python; osrs-benchmarks keeps them apart.
            if field not in row or not isinstance(row[field], kind) \
                    or (kind is int and isinstance(row[field], bool)):
                raise SmokeError(f"{label}: {field} is missing or has the wrong type")
        expected = next(reachable for suite, name, reachable in CASES if (suite, name) == (row["suite"], row["scenario"]))
        if row["outcome"] != "ok":
            raise SmokeError(f"{label}: outcome is {row['outcome']!r} ({row['check'] or row['failure']})")
        if row["expected_reachable"] is not expected or row["reachable"] is not expected:
            raise SmokeError(f"{label}: expected_reachable {row['expected_reachable']}, reachable {row['reachable']}")
        if (row["path_cost"] is None) == row["reachable"]:
            raise SmokeError(f"{label}: path_cost {row['path_cost']} disagrees with reachable")
        if row["total_ns"] is None:
            raise SmokeError(f"{label}: a measured observation needs total_ns")
        if SETTINGS[backend].get("diagnostic") and not isinstance(row.get("graph_reused"), bool):
            raise SmokeError(f"{label}: diagnostic A* observation has no graph_reused")


def run(backend, output_dir):
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / f"{backend}-manifest.json"
    output = output_dir / f"{backend}.json"
    manifest_path.write_text(json.dumps(manifest(backend)))
    env = dict(os.environ, BENCHMARK_RUN_ID=f"ci-smoke-{backend}")
    subprocess.run(["./gradlew", "--quiet", "benchmark",
                    f"--args=--manifest {manifest_path} --output {output}"],
                   cwd=ROOT, env=env, check=True)
    return json.loads(output.read_text())


def main():
    output_dir = ROOT / "build/benchmark-smoke"
    failures = 0
    for backend in SETTINGS:
        try:
            check_envelope(backend, run(backend, output_dir))
            print(f"{backend}: ok")
        except (SmokeError, subprocess.CalledProcessError, ValueError) as error:
            print(f"{backend}: FAILED: {error}", file=sys.stderr)
            failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
