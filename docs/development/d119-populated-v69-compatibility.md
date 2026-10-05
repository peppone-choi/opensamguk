# D119 controlled populated V69 compatibility

This is a separate acceptance case from `D119LegacyV69CompatibilityIT`.
The original zero-row IT, fixture, immutable source manifest and runtime provisioner remain unchanged.

## Source and controlled data

The old source is pinned to `d50177b207897fc6c466095ec9699aab03f57536`.
The existing fixture runs that source's actual Flyway V1…69 and ScenarioImporter,
verifies 1428 cities and the actual empty HWIHA reservation queue, and records its fingerprints.

Only then does `D119PopulatedV69Support` compile the separate Java helper against
the extracted **old** runtime classpath. The helper calls the old
`ReservedTurnRepository.reserve` through its mangled WorldId ABI. It does not use
the candidate repository to prepare its input. It requires the owned loopback
PostgreSQL URL, empty queue and existing public general 1001.

`populated-contract.json` declares two controlled world1/general1001 reservations:

| Slot | Stored historical action | Arg | Brief |
| --- | --- | --- | --- |
| 0 | `che_농지개간` | `{"amount":100}` | `D119 controlled reservation slot 0` |
| 3 | `휴식` | `{}` | `D119 controlled reservation slot 3` |

These nonsecret literals exercise the old storage producer and JSON mapping.
They are controlled test inputs, not an operating dump or authenticated command submission.
They do not prove that the engine admits or executes these actions.
Request IDs remain null; no identity or inbox ownership binding is fabricated.

The JSON contract encodes the historical slot0 action with standard Unicode escapes.
JSON decoding produces the exact stored value shown above for both the seed and
rollback mutation. This representation adds no live product identifier; it does
not change the producer input, expected rows, lint baseline or shared exceptions.

## Candidate identity

The planning candidate is `f0c0478f78ca7737a11e71a61400ff98d75254d7`.
For the allocated proof slot, set `D119_CANDIDATE_SHA` to the **exact final card SHA**.
The test compares its checkout's committed production projection with that SHA
before building the old runtime or starting containers; it rejects dirty or untracked production files.
If a shallow checkout lacks the pinned candidate, only that exact SHA is fetched.

The projection includes backend `src/main`, module Gradle files/properties, root
Gradle configuration and wrappers, `gradle/`, `data/` and `assets/`. Mode, blob and
path are hashed in Git tree order. Test sources, docs, workflows and proof tools
are outside this projection. The actual CI merge-ref, requested candidate,
projection hash and loaded GameApiApplication class hash are recorded separately.

Without a slot pin, future ordinary CI tests its current committed checkout.
It cannot thereby approve an earlier final card: the proof checker separately
compares the recorded production projection with the required final SHA.
When the final card changes, repeat this identity check or revalidate the new candidate.

## Actual assertions

The separate IT requires exactly two stored reservations before boot, after boot,
after production JPA materialization, inside real JDBC flush, and after rollback.
It compares the complete SQL rows including IDs, args, briefs, request IDs and timestamps.
Production JPA fields are compared with those rows. Nonempty `arg` JSON must survive.

The candidate boots its full application with Flyway disabled and JPA validation.
The existing real public HTTP200/topology/tiles/city-key assertions are retained.
The controlled producer may change only general_turn table rows; the schema and
all other table fingerprints must remain identical. Startup and reads must
preserve the whole populated baseline. Actual JdbcFlushExecutor writes a city
population change and a declared general_turn_slot update (slot0 amount101 plus
fixed brief), reads both back and rolls the transaction back. All other reserved
columns and slot3 must remain exact during the write; whole fingerprints
and reservation rows must return to their before values. Flyway history stays V1…69.
Anonymous private HTTP must remain401, and stopping the local PUBLIC admission
source must restore503 through the actual product transport, policy and filter.

## Normal CI slot and artifacts

The entry path is the existing pull_request CI `jvm-core` game-api build/test.
Both D119 classes remain discoverable in ordinary JUnit; no filter bypass, skip
exception, new headless runner or local heavy command is introduced.

Publish this source through C0's allocated ordinary CI slot, separate from #1396.
Mac heavy runs still follow the atomic single-slot resource protocol.
The planning base already has an `always()` full JVM XML artifact, so native
target XML and its embedded receipt are preserved on success too:

- `app/game-api/build/test-results/test/TEST-opensamguk.gameapi.compatibility.D119PopulatedV69CompatibilityIT.xml`
- The XML system-out embeds the whole populated receipt, old provider identities,
  controlled-producer method/origin/data and owned cleanup marker.
- Helper compile/producer logs and the separate JSON receipt remain in the CI
  workspace; archiving those files separately is optional diagnostic evidence.

The base artifact is `jvm-core-xml-attempt-${{ github.run_attempt }}` and its
full game-api XML path includes this class. A green workflow without actual
native target XML and its embedded receipt is not completed A04 evidence.
The failure-only artifact is an additional older path. This change does not edit
the shared CI workflow or require a new artifact step.

Once actual artifacts and the final candidate Git object are available, the read-only gate is:

```sh
python3 tools/compatibility/check_d119_populated_v69.py \
  --xml /absolute/path/to/native-target.xml \
  --expected-candidate <exact-final-card-sha> --repo-root .
```

It requires target tests>0/failures0/errors0/skipped0, a matching receipt inside
that XML, owned cleanup, exact source/contract/helper pins, fixed rows/counts,
full fingerprint equality, actual flush/rollback,401 and503. It does not run fixtures.
`--receipt` can also check a separately archived receipt against the native XML;
omitting it validates the exact embedded receipt directly.

## Remaining boundaries

Actual Gateway publication/JWT, engine reservation execution/SSE, new web,
operating external-scenario equivalence and postV69 migrations remain unverified.
This case does not waive D101's old database transition safety.
Only CEO/C0's exact A04 card can choose (a) after completed evidence; D101-first
(b) remains a separate decision. Source-only static checks are not runtime proof.
