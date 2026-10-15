# Refactor-stack handoff

Status of the middleware refactor stack for the `shortest-path` plugin:
what changed architecturally, where the upstream pull-request chain
stands, the measured merge-readiness evidence, the deferred-work pointer,
and the restack/re-pin choreography for when merges land.

## What the refactor changed

The plugin is being reshaped from a god-object shell into a service
graph: injected singleton services read immutable per-refresh snapshots,
producers declare "what changed" to a refresh coordinator, and the
coordinator is the sole caller of the path scheduler. The dependency
rule, the fourteen leaf packages, the thread-pair annotations, and the
current-vs-target diagrams are in
[architecture-target.md](architecture-target.md). The subsystem-by-
subsystem inventory is [architecture-survey.md](architecture-survey.md);
the move order is [extraction-sequence.md](extraction-sequence.md).

## Stack state

Twenty-one pull requests carry the stack upstream, stacked base-to-tip —
the twenty-first is the consolidated test-suite rework appended after the
refactor's tip.
**No stack PR has merged upstream — every one remains open, and all but
the stack root are drafts by design.** Merge timing is upstream's lane;
nothing here asserts readiness on the reviewer's behalf.

| # | PR | Purpose | Base |
|---|----|---------|------|
| 1 | #707 | Pathfinder state hardening — consumable rejection under the teleportation-item modes, filtered targets on the `Targets` API | upstream `master` (stack root, non-draft) |
| 2 | #708 | Transport eligibility middleware — `RequirementContext` snapshot, gate chain, `HeldItems` projection | #707 |
| 3 | #709 | Bank-pickup consumption ledger (issue #636) | #708 |
| 4 | #710 | Path revalidation — post-search consumption validation, bounded re-planning | #709 |
| 5 | #716 | Settings service — typed config access, overrides, panel read/write/listen contract | #708 |
| 6 | #721 | Item-state service — carried/banked item pools, bank pickups | #716 |
| 7 | #722 | Spirit-tree service — grown-state detection, travelable tree set | #721 |
| 8 | #726 | POH service — portals, mounted items, landing facts | #722 |
| 9 | #732 | `BankVisitState` — banked/unbanked enum replaces the positional boolean at seams | #726 |
| 10 | #733 | Path scheduler + `DebugState` diagnostics | #732 |
| 11 | #734 | Refresh coordinator — invalidation channels, sole scheduler caller | #733 |
| 12 | #747 | Sailing service — boat-state producer | #734 |
| 13 | #775 | Widget + UI geometry | #747 |
| 14 | #776 | Plugin-message API, menu verbs, transport presentation | #775 |
| 15 | #777 | Path-rendering overlays re-pointed at the injected seams | #776 |
| 16 | #778 | Highlight overlays re-pointed at the injected seams | #777 |
| 17 | #792 | `SkillLevels` — typed skill-level value type | #778 |
| 18 | #793 | `ResourceLoader` — TSV data loading behind a typed entry point | #792 |
| 19 | #794 | Widget-scraping machinery (`GameWidgets`) | #793 |
| 20 | #711 | Config panel rework + teleport restrictions | #794 |
| 21 | #807 | Test-suite rework — consolidated test-only PR (shared lazy fixtures, stale-API repair, capped test forks) (stack tip) | #711 |

Chain shape: #707 is the root; #709/#710 are a short fork off #708 (the
issue-#636 work); the main line continues #708 → #716 → … → #711.
The integrated branch pair — tooling `1.22.0` pinning submodule `1.22.0`
at `099c7ff2` — accumulates all twenty-one in chain order regardless of
merge timing. The submodule branch is a **linear stack**: one commit per
PR in chain order (`stack: #707 … stack: #807`), no merge commits, so its
history reads as the chain rather than integration plumbing.

## Merge-readiness evidence

Measured on the rebuilt `1.22.0` tip against `upstream/master` at the
recorded base `c4896015`. The upstream tip has since advanced by one
workflow-only commit (`262efad7` — Pages site wiring, zero pathfinding
or data surface).

- **Route delta — zero-delta.** A/B over the nine committed dashboard
  datasets, 1,176 scenario runs per side: zero differing outcome rows
  (reachability, path length, full path, assertions). The 113 red rows
  are byte-identical to the upstream control's own failure set at the
  same datasets.
- **Performance — same-or-faster.** Canonical corpus benchmark
  (135 routes × 4 account profiles × 3 repetitions = 1,620 observations
  per side, 531 identical-outcome cases compared): raw total elapsed
  −0.44%, per-case median-of-3 −1.35%, nanoseconds per state popped
  −0.91%. Dashboard suite combined elapsed −0.18%; the worst per-dataset
  timing deltas did not reproduce on repeat runs (single-shot noise).
- **One attributed behavioural delta.** The canonical corpus surfaces 9
  logical cases (27 observation rows) with changed path costs — all
  trace to #711's hot-air-balloon data fix (balloon legs now require
  their in-game log cost, commit `8cc55185`). The engine is
  behaviour-preserving: identical nodes-explored on re-run datasets,
  zero reachability drift, and optimal paths still found under the
  corrected requirements.
- **Local gate.** The four-tier maintenance verify on the rebuilt pin:
  compile, lint, and collision-map edge-diff green; the dashboard tier's
  113 reds are exactly the upstream baseline failure set — zero new
  failures.
- **CI sweep.** All 38 open stack PRs (20 plugin + 18 paired tooling
  PRs) carry zero failing/cancelled/timed-out checks. Stack-root #707
  shows a merge-conflict flag against the upstream tip — an
  upstream-side rebase item, not a check failure.

## Deferred work

Deferred items discovered across the milestone are classified in a
single register — upstream-blocked items, upstream follow-ups,
post-milestone features, corpus/harness debt, and process debt. The
committed summary is [DEFERRED.md](DEFERRED.md).

## What remains

- **Upstream review and merge authorization.** All twenty-one PRs
  remain open pending review; merging stays gated on upstream's
  go-ahead.
- **PR-body boundary references.** Five bodies (#707, #708, #709, #710,
  #711) do not yet carry the architecture-boundary reference — recorded
  for a merge-time body pass rather than rewritten in flight.
- **#707 adversarial-test flag.** Its consumable-rejection delta has no
  in-PR adversarial test; mode-matrix coverage ships in #708's test
  class on the same tree. A dedicated test lands post-merge only if
  upstream wants it.
- **Registered upstream follow-ups.** The `DebugOverlayPanel`
  constructor seam (RuneLite's `OverlayPanel` requires the plugin
  instance) plus four provisional seam edges — each repair is deferred
  because landing it mid-stack would re-diff every descendant PR.
- **Follow-up test-suite rework.** Landed as draft PR #807 (branch
  `test/suite-rework`, consolidated test-only diff), stacked on #711 at
  the stack tip and stacked into the `1.22.0` integration branch — the
  tooling pin sits at the linear head `099c7ff2`, so the milestone's
  test-side debt is upstream-visible pending review.

## Merge choreography — per-merge restack and re-pin

Every stack PR below the tip is stacked on its predecessor, so each
upstream merge triggers the same rule: **the remaining draft
descendants rebase onto the merged `upstream/master` in chain order,
each descendant's paired `chore/stage-*` tooling branch re-points its
plugin pin at the descendant's new published head, the merged PR's own
stage pair retires (its content now lives on `master`), and the
integration branch's gitlink advances on the next pairing.**

| When this merges | Restack next | Re-point pin |
|------------------|--------------|--------------|
| #707 | #708 onto merged `master`; #709/#710 follow onto the new #708 | `chore/stage-642-646` → published #708 head |
| #708 | #709, #710, and #716 (both lines off #708) onto merged `master` | `chore/stage-642-646` retires; `chore/stage-settings` → published #716 head |
| #709 | #710 onto merged `master` | — (unpaired fork child) |
| #710 | — (fork leaf; nothing descends) | — |
| #716 | #721 onto merged `master` | `chore/stage-item-state` → published #721 head |
| #721 | #722 onto merged `master` | `chore/stage-spirit-trees` → published #722 head |
| #722 | #726 onto merged `master` | `chore/stage-poh` → published #726 head |
| #726 | #732 onto merged `master` | `chore/stage-bank-visit-state` → published #732 head |
| #732 | #733 onto merged `master` | `chore/stage-path-scheduler` → published #733 head |
| #733 | #734 onto merged `master` | `chore/stage-refresh-coordinator` → published #734 head |
| #734 | #747 onto merged `master` | `chore/stage-sailing` → published #747 head |
| #747 | #775 onto merged `master` | `chore/stage-widget-geometry` → published #775 head |
| #775 | #776 onto merged `master` | `chore/stage-plugin-message-api` → published #776 head |
| #776 | #777 onto merged `master` | `chore/stage-path-overlays` → published #777 head |
| #777 | #778 onto merged `master` | `chore/stage-highlight-overlays` → published #778 head |
| #778 | #792 onto merged `master` | `chore/stage-skill-levels` → published #792 head |
| #792 | #793 onto merged `master` | `chore/stage-resource-loader` → published #793 head |
| #793 | #794 onto merged `master` | `chore/stage-widget-scraping` → published #794 head |
| #794 | #711 onto merged `master` | `chore/stage-701` → published #711 head |
| #711 | #807 onto merged `master` | — (test-only; rides the integration re-pin) |
| #807 | stack complete — the integrated branch carries the merged chain | — |

Notes on the table:

- `#707`, `#709`, and `#710` have no paired stage branch (recorded
  coverage gap); they ride the chain-neighbour pairings and the
  submodule test suite instead.
- A stage pin always names an `ls-remote`-verified published head —
  never a local tip — and pins move only under
  `git push --force-with-lease`.

**Other upstream merges being watched** (each carries its own recorded
trigger in the deferred-work register): #633 (on merge: the #542
regression row flips and the issue closes), plus #635, #653, #656,
#658, #659, and #701.
