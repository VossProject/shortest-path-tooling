# Canonical corpus

The canonical routes. This directory was the `shortest-path-corpus` repository; its history was
imported here.

| Path | Contents |
|---|---|
| `corpus/routes-v1.json` | canonical routes, run by the `canonical` suite with every canonical account |
| `corpus/excluded-routes-v1.json` | routes excluded from the corpus, with reasons |
| `schemas/` | the AccountBuild, route API, route policy and place JSON schemas of the standalone web planner |

The canonical accounts `early`, `mid`, `end`, `maxed` are Java only:
`accounts/…/canonical/CanonicalAccounts.java`. `end` and `maxed` are quest-cape accounts.

## Route format

Each route in `corpus/routes-v1.json` has these consumer-facing fields:

- `id`: stable route identifier;
- `name`: human-readable route label;
- `start`, `target`: authoritative `[x, y, plane]` coordinates;
- `startName`, `targetName`: human-readable endpoint labels;
- `startSource`, `targetSource`: opaque provenance strings for auditing, not a parseable API;
- `allowTransports`: whether the pathfinder may use transports. `false` is a walking route: no teleports
  (home teleports and house mounted items included) and no transport network, only what walking needs
  (doors, stairs and the like);
- `tiers`: benchmark suites containing the route (`smoke`, `standard`, `full`);
- `negativeProfiles`: optional account profiles expected to find the route unreachable. These are
  hand-maintained expectations, not derived from any implementation.

`CanonicalCorpusTest` checks the file.
