"""The benchmark smoke check accepts what osrs-benchmarks accepts and rejects the rest."""

import copy
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import benchmark_smoke as smoke  # noqa: E402


def envelope(backend="astar"):
    rows = []
    for suite, name, reachable in smoke.CASES:
        rows.append({
            "run_id": "ci", "suite": suite, "scenario": name, "category": "gps-natural", "profile": "maxed",
            "repetition": 0, "expected_reachable": reachable, "outcome": "ok", "check": "",
            "reachable": reachable, "path_cost": 363 if reachable else None,
            "path_length": 120 if reachable else 0, "total_ns": 1000, "allocated_bytes": 4096,
            "termination_reason": "", "failure": "",
            "graph_reused": True,
        })
    return {"format_version": 2, "observations": rows,
            "execution_metadata": {
                "algorithm": backend,
                "runtime": {"java_version": "17", "vm_name": "vm", "vm_version": "1"},
                "memory": {field: 1 for field in smoke.MEMORY},
                "world": {field: 1 for field in smoke.WORLD + smoke.ASTAR_WORLD}}}


def test_accepts_a_good_envelope():
    smoke.check_envelope("astar", envelope())


@pytest.mark.parametrize("break_it", [
    lambda e: e.update(format_version=1),
    lambda e: e["execution_metadata"].update(algorithm="legacy"),
    lambda e: e["execution_metadata"]["runtime"].pop("java_version"),
    lambda e: e["observations"].pop(),
    lambda e: e["observations"][0].pop("category"),
    lambda e: e["observations"][0].update(repetition=True),
    lambda e: e["observations"][0].update(outcome="unexpected"),
    lambda e: e["observations"][1].update(reachable=True, path_cost=12),
    lambda e: e["observations"][0].update(path_cost=None),
    lambda e: e["observations"][0].update(total_ns=None),
    lambda e: e["observations"][0].pop("graph_reused"),
    lambda e: e["observations"][0].pop("allocated_bytes"),
    lambda e: e["execution_metadata"]["memory"].pop("peak_heap_bytes"),
    lambda e: e["execution_metadata"]["world"].pop("search_tiles"),
])
def test_rejects_what_osrs_benchmarks_would(break_it):
    bad = copy.deepcopy(envelope())
    break_it(bad)
    with pytest.raises(smoke.SmokeError):
        smoke.check_envelope("astar", bad)
