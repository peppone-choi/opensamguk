# Review: CheckEmperior Tail Closure And Ambient Boundary

## Verdict

`full-gate-green-awaiting-independent-review`.

The former fail-fast boundary at `legacy/devsam-core/hwe/func_gamerule.php:725` is superseded.
`checkEmperior` now executes the PHP `:696-939` tail in source order through the daemon recorder and
JDBC flush path. `RaiseNPCNation` remains a separate, proof-backed ambient-RNG quarantine at
`legacy/devsam-core/hwe/sammo/Event/Action/RaiseNPCNation.php:232`.

## Closed Tail

| PHP lines | Runtime effect |
| --- | --- |
| 696-725 | Single active nation and static city-catalog guards, then final statistic insert. |
| 727-743 | National history and active unique-auction closure in close-date order. |
| 745-767 | Unifier inheritance award, United event, merge/apply, `isunited=2`, refresh-limit multiplier, hall writes. |
| 769-904 | Population/chief/rank summaries, old-general and nation archives, winner update, `emperior` insert. |
| 906-911 | Hidden-seed global history and yearbook settlement. |
| 913-939 | Level-12-to-5 chief selection, at most two user chiefs, three `RaiseInvaderMessage` difficulties per chief. |

The `tiger` and `eagle` strings read persisted `rank_data`, apply pending recorder deltas, preserve
stable row order for equal scores, format values with thousands separators, and enforce PHP limits
5 and 7. Invader offers emit the three exact difficulty texts and argument arrays. Every offer
writes the receiver row before the system-sender row and marks the receiver's `newmsg` state.

## Persistence

- `V27__unification_emperior.sql` creates the missing archive table.
- `WorldSnapshotLoader` restores active `serverId`/`ngGameId`, archived nation IDs, inheritance
  values, unique occupancy, rank values, all statistic rows, newest-first national history, and the
  actual `ng_games` server count.
- `ChangeRecorder` and `JdbcFlushExecutor` carry statistic, inheritance, hall, archive, winner,
  emperor, yearbook, message, and old-general writes without JPA daemon writes.

## Verification

- `WorldActionCheckEmperiorContextTest` verifies rank strings, message difficulty/order/shape, and
  `newmsg` mutation.
- `WorldActionContextRngTest` verifies static-map guards and the completed national-history path.
- `WorldSnapshotLoaderArchiveIT` verifies restart selection and archive state.
- The emperor row selects the first historical row at the maximum nation count, the latest row for
  personal/special/aux summaries, the historical maximum general count, and merges pending national
  history ahead of persisted newest-first history exactly as the PHP queries do.
- Focused engine run: `BUILD SUCCESSFUL`, failures/errors 0.
- Focused P0 rerun: 6 suites / 35 tests, failures/errors/skips 0.
- Forced five-module run: `BUILD SUCCESSFUL in 30m 44s`, 481 suites / 4,406 tests, failures/errors 0,
  skip 1. Canonical backend gate: `BUILD SUCCESSFUL in 15m 52s`, XML 481 / 4,406 green.

Final acceptance now requires only the fresh independent parity review.
