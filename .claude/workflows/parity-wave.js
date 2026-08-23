export const meta = {
  name: 'parity-wave',
  description:
    'Opt-in historical frozen-regression maintenance under ADR-LITE-042. Approved ADR/spec/current implementation remains product authority. Fan out parity-close only across the explicitly supplied PHP command codes; never use this workflow as a prerequisite for new product work.',
  phases: [
    { title: 'Foundation', detail: 'one agent widens shared files: stubs + CommandRegistry + intakeCodes + wire variants for all N codes' },
    { title: 'Close', detail: 'per command (parallel, disjoint files): golden-capturer → parity-porter(fill stub) → parity-gate-runner(loop until green)' },
    { title: 'Integrate', detail: 'per-command FE submit-wiring (sequential), then one adversarial parity-reviewer pass over the whole batch' },
  ],
}

// --- inputs: command codes from $availableGeneralCommand / $availableChiefCommand ---
const codes = Array.isArray(args)
  ? args.filter(Boolean)
  : typeof args === 'string'
    ? args.split(/[\s,]+/).filter(Boolean)
    : []

if (codes.length === 0) {
  log('parity-wave: no command codes. Pass args: ["출병","che_농지개간",...] — PHP codes that are open gaps (ported-or-not, but absent from intakeCodes in CommandWireMapper.kt).')
  return { error: 'no command codes provided' }
}
log(`parity-wave over ${codes.length} gap(s): ${codes.join(', ')}`)

// Chunk size: max 3 commands per wave to keep agent outputs under 128K
const CHUNK_SIZE = 3
const codeChunks = []
for (let i = 0; i < codes.length; i += CHUNK_SIZE) {
  codeChunks.push(codes.slice(i, i + CHUNK_SIZE))
}
if (codeChunks.length > 1) {
  log(`Split into ${codeChunks.length} chunks of max ${CHUNK_SIZE} commands each (128K output limit).`)
}

// Schemas — force structured output, no free prose
const FOUNDATION_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    widenedFiles: { type: 'array', items: { type: 'string' }, description: 'list of files modified in foundation phase' },
    stubClassNames: { type: 'array', items: { type: 'string' }, description: 'list of stub classes created' },
    compileOk: { type: 'boolean' },
    summary: { type: 'string', maxLength: 2000 },
  },
  required: ['widenedFiles', 'stubClassNames', 'compileOk'],
}

const GOLDEN_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    code: { type: 'string' },
    fixturePath: { type: 'string', description: 'path to golden fixture, or empty if RNG-free' },
    rngFreeReason: { type: 'string', maxLength: 500, description: 'why RNG-free, if applicable' },
    sha256: { type: 'string', description: 'sha256 of fixture, or empty' },
    quarantine: { type: 'string', maxLength: 500, description: 'quarantine note if uncapturable' },
  },
  required: ['code', 'fixturePath'],
}

const PORT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    code: { type: 'string' },
    filesWritten: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string', maxLength: 1000 },
  },
  required: ['code', 'filesWritten'],
}

const GATE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    code: { type: 'string' },
    green: { type: 'boolean' },
    drawsAsserted: { type: 'number' },
    summary: { type: 'string', maxLength: 2000 },
    quarantine: { type: 'string', maxLength: 1000, description: 'if not green: the proven quarantine + backlog note; else empty' },
    divergence: { type: 'string', maxLength: 2000, description: 'first divergence details if RED' },
  },
  required: ['code', 'green', 'summary'],
}

const FE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    code: { type: 'string' },
    filesWired: { type: 'array', items: { type: 'string' } },
    hasModal: { type: 'boolean' },
    summary: { type: 'string', maxLength: 1000 },
  },
  required: ['code', 'filesWired'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    blockers: { type: 'array', items: { type: 'string' }, description: 'list of BLOCKER findings, path:line: severity: problem' },
    warnings: { type: 'array', items: { type: 'string' }, description: 'list of WARNING findings' },
    notes: { type: 'array', items: { type: 'string' }, description: 'list of INFO notes' },
    summary: { type: 'string', maxLength: 2000 },
  },
  required: ['blockers', 'warnings', 'notes', 'summary'],
}

// Process each chunk sequentially (barrier between chunks to avoid 128K accumulation)
const allClosed = []
const allGreen = []
const allRed = []

for (let chunkIdx = 0; chunkIdx < codeChunks.length; chunkIdx++) {
  const chunk = codeChunks[chunkIdx]
  log(`=== Chunk ${chunkIdx + 1}/${codeChunks.length}: ${chunk.join(', ')} ===`)

  // === Phase 0: Foundation — shared files touched ONCE per chunk ===
  phase('Foundation')
  const foundation = await agent(
    `FOUNDATION-FIRST shared-file widening for a parity wave. Touch the SHARED files exactly once for ALL these command codes so the parallel porters that follow never co-widen them (CLAUDE.md: parallel families must be disjoint).
Command codes: ${JSON.stringify(chunk)}.
For EACH code:
1. Read its PHP class — legacy/devsam-core/hwe/sammo/Command/{General,Nation}/<code>.php — for scope + signature.
2. Create a COMPILING STUB at logic/src/main/kotlin/opensamguk/logic/actions/<scope>/<CheXxx>.kt mirroring a sibling's class shape, with resolve() left as TODO(포팅). Do NOT implement behavior — the per-command porter fills it.
3. Register the code in logic/.../actions/CommandRegistry.kt (when-arm + import).
4. Add it to intakeCodes + toCommand in app/game-api/.../reserve/CommandWireMapper.kt; add a wire variant in common/wire/TurnDaemonCommand.kt if absent.
After all codes: confirm :logic:compileKotlin + :logic:compileTestKotlin BUILD SUCCESSFUL (gradle via ctx_execute, Java 21, verify by output tail not exit code). Return ONLY the structured schema fields. Do NOT run any gate. Do NOT fill any resolve().`,
    { label: `foundation-chunk${chunkIdx}`, phase: 'Foundation', schema: FOUNDATION_SCHEMA, agentType: 'parity-porter' }
  )

  // === Phase 1: per-command close, pipelined ===
  const results = await pipeline(
    chunk,

    // stage 1 — capture golden
    (code) =>
      agent(
        `Capture the real PHP golden for command ${code} via tools/php-golden (Docker, scenario_1010). If the command is RNG-free, STATE WHY (quote the PHP showing no rand/RandUtil/pickOne) and skip the fixture; else write logic/src/test/resources/golden/<area>/${code}-fixtures.json and prove two-run sha256-identical. Faithful, never fabricate — uncapturable ⇒ quarantine-with-proof. Return ONLY the structured schema fields.`,
        { label: `golden:${code}`, phase: 'Close', schema: GOLDEN_SCHEMA, agentType: 'golden-capturer' },
      ).then((golden) => ({ code, golden })),

    // stage 2 — fill stub
    (prev) =>
      agent(
        `FILL the already-registered stub for ${prev.code} (foundation created + registered it). Port resolve() draw-for-draw faithfully and write the *GoldenTest replaying the committed fixture. Touch ONLY logic/src/main/kotlin/.../actions/<scope>/<Che>.kt and logic/src/test/kotlin/.../golden/Che<한글>GoldenTest.kt — do NOT edit CommandRegistry or CommandWireMapper (foundation owns those). Return ONLY the structured schema fields.`,
        { label: `port:${prev.code}`, phase: 'Close', schema: PORT_SCHEMA, agentType: 'parity-porter' },
      ).then((port) => ({ ...prev, port })),

    // stage 3 — gate
    (prev) =>
      agent(
        `Run the golden gate for ${prev.code}: JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests '*GoldenTest' --rerun-tasks via ctx_execute (host redirects gradle). Verify by test XML (logic/build/test-results/test/*.xml), NOT exit code. If RED, report the FIRST divergence precisely (draw index / expected-vs-actual / first byte-differing log line) so the porter can fix the Kotlin. Do NOT weaken the test or edit the golden. Return ONLY the structured schema fields.`,
        { label: `gate:${prev.code}`, phase: 'Close', schema: GATE_SCHEMA, agentType: 'parity-gate-runner' },
      ).then((gate) => ({ ...prev, gate })),
  )

  const closed = results.filter(Boolean)
  const green = closed.filter((r) => r.gate && r.gate.green)
  const red = closed.filter((r) => !r.gate || !r.gate.green)
  log(`chunk ${chunkIdx + 1} gates: ${green.length}/${closed.length} green${red.length ? ` — RED: ${red.map((r) => r.code).join(', ')}` : ''}`)

  allClosed.push(...closed)
  allGreen.push(...green)
  allRed.push(...red)
}

// === Phase 2: integrate — FE submit-wiring + review ===
phase('Integrate')

// FE wiring: sequential, but schema-limited output
for (const r of allGreen) {
  await agent(
    `Wire the FE submit path for ${r.code} in web/game (form / CommandModal for arg-bearing → Next route handler → game-api intake). Match existing action-page style; httpOnly-cookie auth unchanged. Return ONLY the structured schema fields.`,
    { label: `fe:${r.code}`, phase: 'Integrate', schema: FE_SCHEMA, agentType: 'fe-submit-wirer' }
  )
}

// Review: per-command chunked to avoid 128K
// If >5 green commands, split review into batches
const REVIEW_CHUNK = 5
const greenCodes = allGreen.map((r) => r.code)
const reviewResults = []
for (let i = 0; i < greenCodes.length; i += REVIEW_CHUNK) {
  const batch = greenCodes.slice(i, i + REVIEW_CHUNK)
  const review = await agent(
    `Adversarial opt-in historical regression review of commands: ${batch.join(', ')}. Check selected frozen RNG evidence, PhpRound (half-away), historical log comparison, flush-delta-not-inline, intakeCodes presence, one-daemon-write-rule, insertion-order (LinkedHashMap). Use code-review-graph detect_changes/get_impact_radius before Grep. Return ONLY structured findings — no prose.`,
    { label: `review-batch${i}`, phase: 'Integrate', schema: REVIEW_SCHEMA, agentType: 'parity-reviewer' }
  )
  reviewResults.push(review)
}

// Aggregate review findings (small, since each is structured)
const allBlockers = reviewResults.flatMap((r) => r.blockers || [])
const allWarnings = reviewResults.flatMap((r) => r.warnings || [])
const allNotes = reviewResults.flatMap((r) => r.notes || [])

return {
  totalCodes: codes.length,
  chunks: codeChunks.length,
  green: allGreen.map((r) => r.code),
  red: allRed.map((r) => ({ code: r.code, why: r.gate?.summary || 'no gate result' })),
  review: {
    blockers: allBlockers,
    warnings: allWarnings,
    notes: allNotes,
    blockerCount: allBlockers.length,
    warningCount: allWarnings.length,
  },
  next: allRed.length
    ? `Fix RED gates (${allRed.map((r) => r.code).join(', ')}) then re-run parity-wave (resume cached green); do NOT /parity-ship until all green.`
    : allBlockers.length
      ? `Fix ${allBlockers.length} BLOCKERS then /parity-ship.`
      : 'All green — hand the batch to /parity-ship.',
}
