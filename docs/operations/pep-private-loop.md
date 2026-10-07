# pep PRIVATE loop (D143)

`deploy.yml` selects the final main source and waits for its CI. The reusable
`pep-loop.yml` builds API, engine and web images from that source. `pep-refresh`
and `promote-game-server` use refresh; `pep-reset` and `reset-game-server` use reset.
All entry points share `pep-private-loop` concurrency, with no cancellation of a
running operation. The VM helper also acquires the existing production flock.

The helper examines **every commit since the last successful apply**, including
reverted paths. Migration, seed, scenario, data/curated and engine boot/config
changes upgrade any refresh to reset. Its initial cursor is the given initialized
main `b7dc`; subsequent cursors are written only after smoke passes. A newer main
observed after image pulls skips the stale build before mutation; the queued run
builds the latest main. No caller can supply another server or an arbitrary image.

Only exact `pep` / `opensamguk-spep` is targeted. The helper reuses the existing
control repository Compose and server env through Compose's `--env-file`; it does
not source, print or rewrite secrets. API and web run as the existing validation
containers, without service ports or aliases. The existing read-only fullbundle
mount and bake binding are preserved. The successful non-secret runtime override
is retained at `/home/peppone_choi/opensamguk-docker/pep-loop.compose.json`.

Reset stops/removes only pep consumers and deletes exactly `spep-game-pgdata` and
`spep-game-redisdata`, once, without backup. It seeds 3190/world1/gen0/tick3600 with
numeric maxgeneral50 in config/game_env and creation block1. Ordinary engine boot
with its daemon disabled seeds first; a read-only SQL check precedes enabling the
daemon. First tick requires successfulTicks>=1, failedTicks=0, recoveryReady,
loopAlive and a persisted lastTurnTime. The date can remain 190/1/상. A refresh can
wait up to 65 minutes for the restarted engine's next normally scheduled tick.

The K10 A API probe uses PRIVATE container-local paths: actuator health, basic
info, map preview and all assets of the currently bound fullbundle. Optional
`PEP_SMOKE_JWT` adds `/api/my-page`; missing supply is a BOARD → CEO account/JWT
item, not an auth/key/provider provisioning gate. The web check uses its internal
port. Results go to the workflow summary; no browser measurement or receipt
harness is included. Failure closes only pep API/web/engine and keeps the source
cursor; it never retries deletion or restores data automatically.

Enabling these currently disabled entry workflows is C0's post-merge connection
step. This PR does not enable workflows or dispatch operations. Root bootstrap,
controller, gateway, PUBLIC routes and other servers are unchanged. Only the
offline helper tests and workflow syntax run locally; JVM/web image builds and
smoke belong to CI and the later authorized operation.
