# Deferred Work Register — Summary

Deferred work discovered across the milestone is classified into a single
register so nothing tracked gets lost between phases. The full register —
one row per item with rationale, unblock condition, candidate milestone,
and provenance — is tracked alongside the milestone plan. This document is
the committed summary: it lists every open item that has a public anchor
(upstream issue or PR, or a `docs/` page) and gives per-class counts for
the rest.

## Classes

| class | meaning |
|-------|---------|
| `upstream-blocked` | waiting on an upstream merge or maintainer decision |
| `upstream-followup` | needs its own upstream PR / follow-up after merges |
| `post-milestone-feature` | feature work scheduled after this milestone |
| `corpus/harness-debt` | test-corpus staleness or harness capability debt |
| `process-debt` | bookkeeping / workflow debt |

## Open items with public anchors

**Upstream-blocked (open upstream PRs/issues gating the item):**

- Stack merge authorization — gated on the published merge condition
  (route delta unchanged vs upstream master, same-or-faster performance)
  and the user's explicit go-ahead; evidence posts to epic
  Skretzo/shortest-path#714.
- Skretzo/shortest-path#701 — teleport-item restriction UI and the
  balloon-log-basket unlock ship in that draft PR; also pending a
  full-panel dev-client check before it can leave draft.
- Skretzo/shortest-path#655 — Xeric's Honour config toggle (issues
  #214/#380); a real unlock var from upstream would replace the toggle.
- Skretzo/shortest-path#656 — Dragontooth passage toggle (issue #427).
- Skretzo/shortest-path#633 — planted spirit-tree fix; on merge the
  #542 regression row flips and the issue closes.
- LlemonDuck/easy-teleports#31 — friendly destination names (#480) prefer
  the integration path; a built-in alias map is the fallback.
- Open-PR merge watch — #633, #635, #653, #656, #658, #659, #701 and the
  refactor stack; each merge triggers restack/re-pin choreography.

**Upstream-followup (each needs its own upstream PR after merges):**

- Provisional seam repairs deferred out of the in-flight stack diff —
  four documented edges plus the `DebugOverlayPanel` constructor,
  adjudicated a named lint exception: RuneLite's `OverlayPanel`
  requires the plugin instance, so the parameter keeps the
  `ShortestPathPlugin` type only to satisfy `super(plugin)`; widening
  it to `net.runelite.client.plugins.Plugin` is deferred post-merge
  for the same reason.
- `UNLOCK_*` parser hardening — typo'd unlock tokens currently abort
  plugin startup; per-record validation or a TSV-lint name check.
- Unlock-branch null rows in `getStaves()`/`getOffhands()`,
  `UNLOCK_X=N>1` silently accepted, `isUnlockOnly`/`satisfiedByItems`
  predicate drift, and the overlay transport filter ignoring declared
  unlocks.
- Skretzo/shortest-path#601 / #602 — verified fixed locally, still open
  upstream; close once the fixing-PR attribution is confirmed.
- Optional `clear`+`expected` compare-and-set complement for the
  getTarget plugin-message API (#531 follow-up, if upstream wants the
  race closed).
- Skretzo/shortest-path#707, #708, #709, #710, #711 — five stack PR
  bodies lack the architecture-boundary reference; they get stamped at
  the merge-time body pass.
- Skretzo/shortest-path#707 — the consumable-rejection semantics delta
  carries no in-PR adversarial test (mode-matrix coverage ships in
  #708's test class on the same tree); a dedicated test lands
  post-merge only if upstream wants it.

**Post-milestone features:**

- Sailing domain (Skretzo/shortest-path#351, #495, #370, #293) —
  dock-to-dock edges, boat roster, hull-footprint routing, shipwright
  recovery, boat teleports; its own feature phase.
- Grammar extensions — weight requirement (#354, Mogre Camp), inverse
  quest-state gate (#396, Ape Atoll jail warp), teleport penalties
  (#494).
- Account/respawn features — active-respawn tracking (#502; the
  Prifddinas config option already shipped), maxed-house umbrella
  (#96), POH portal cost/list options (#543, #545).
- Verdict/`RejectionReason` surfacing — dashboard report, debug overlay,
  panel, and a plugin-message verdict API; needs an owned-state decision.
- Harness/UI ideas — `leagues/` leaf extraction, POH layout model,
  tooltip UX for rejected transports, path-marker axis, config presets,
  wiki ground-truth coverage, CI scheduling of validation/regeneration.

**Corpus/harness debt:**

- Ancient Cavern whirlpool row (Skretzo/shortest-path#374) — whirlpool
  taken but the search stops two tiles short; investigate pocket
  sealing vs a missing landing transport.
- Entrana dungeon wilderness exit (#350) — sealed by an upstream
  collision-map regeneration; investigate correct game data vs regen
  artifact.
- A handful of deferred probes.

**Process debt:** shadow-file lint debt on a few old issue records,
close-path hardening in the issue-sync tooling, maintenance-script
modularization, and standing routing policies (new unlock families,
speculative dumpers, triage routing).

## Resolved during the test-suite rework

The milestone's test-suite-rework pass discharged these register items
(kept here for provenance; they are no longer open):

- Dependency-boundary lint promotion — the architecture dependency-rule
  lint is now a blocking tier in the maintenance verify gate and in CI
  — resolved during the test-suite rework
- Stale route-dataset expectations — the committed expected lengths were
  recaptured on the merged state with per-row triage; the mass of reds
  traced to one harness defect (dead var-bypass overrides), not to
  routing regressions — resolved during the test-suite rework
- Parallel-worker determinism replay — a sequential vs pooled A/B over
  the full corpus produced identical outcome records — resolved during
  the test-suite rework
- Corpus account-profiles adapter — corpus routes now compile through
  the shared account model — resolved during the test-suite rework
- Wire-vs-harness override-seam unification — superseded by removal: the
  third dispatch seam was deleted outright in the suite migration, and a
  parity test pins the two surviving seams — resolved during the
  test-suite rework
- The test-suite rework itself — the confirmed next-milestone item is
  complete — resolved during the test-suite rework
- F2P/league scenario verification — the harness still lacks
  members-world and league stubs, so these states stay unverifiable
  — re-deferred during the test-suite rework

## Counts

89 open register rows and 34 superseded/resolved marks, swept from 36
deferred-item sources (per-phase deferred lists, deferred-idea blocks in
phase notes, the issue residual ledger, state notes, and the integration
findings ledger). All sources are marked rolled up; this register is the
one open ledger going forward.
