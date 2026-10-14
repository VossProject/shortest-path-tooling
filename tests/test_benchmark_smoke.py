"""The benchmark smoke check accepts what the harness accepts and rejects the rest."""

import copy
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import benchmark_smoke as smoke  # noqa: E402


def envelope(project="shortest-path-exact"):
    rows = []
    for route, profile, reachable in smoke.CASES:
        rows.append({
            "schema_version": 2, "protocol_version": 1, "run_id": "ci", "project": project,
            "implementation": "shortest-path-java", "algorithm": smoke.PROJECTS[project]["algorithm"],
            "route_id": route, "route_name": "a route", "account_profile": profile, "repetition": 0,
            "reachable": reachable, "path_cost": 363 if reachable else None,
            "expected_reachable": reachable, "correctness": "expected", "total_ns": 1000,
            "graph_reused": True,
        })
    return {"protocol_version": 1, "observations": rows,
            "execution_metadata": {"implementation": "shortest-path-java",
                                   "algorithm": smoke.PROJECTS[project]["algorithm"],
                                   "synthetic_benchmark_time": smoke.CLOCK}}


def test_accepts_a_good_envelope():
    smoke.check_envelope("shortest-path-exact", envelope())


@pytest.mark.parametrize("break_it", [
    lambda e: e.update(protocol_version=2),
    lambda e: e["execution_metadata"].update(synthetic_benchmark_time=0),
    lambda e: e["execution_metadata"].update(algorithm="legacy"),
    lambda e: e["observations"].pop(),
    lambda e: e["observations"][0].pop("route_name"),
    lambda e: e["observations"][0].update(repetition=True),
    lambda e: e["observations"][0].update(correctness="reachability_mismatch"),
    lambda e: e["observations"][1].update(reachable=True, path_cost=12),
    lambda e: e["observations"][0].update(path_cost=None),
    lambda e: e["observations"][0].update(total_ns=None),
    lambda e: e["observations"][0].pop("graph_reused"),
])
def test_rejects_what_the_harness_would(break_it):
    bad = copy.deepcopy(envelope())
    break_it(bad)
    with pytest.raises(smoke.SmokeError):
        smoke.check_envelope("shortest-path-exact", bad)
