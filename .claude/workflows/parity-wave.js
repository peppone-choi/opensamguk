export const meta = {
  name: 'parity-wave',
  description:
    'Fan-out the parity-close pipeline across N command gaps. Foundation-first (shared-file stubs + CommandRegistry + intakeCodes + wire variants widened ONCE), then per-command golden→port→gate in parallel (disjoint files), then batch FE submit-wiring + one adversarial review. Hand the green set to /parity-ship. Args: a list of PHP command codes, e.g. ["출병","che_농지개간","che_몰수"].',
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

const GATE = {
  type: 'object',
  additionalProperties: false,
  properties: {
    code: { type: 'string' },
    green: { type: 'boolean' },
    drawsAsserted: { type: 'number' },
    summary: { type: 'string' },
    quarantine: { type: 'string', description: 'if not green: the proven quarantine + backlog note; else empty' },
  },
  required: ['code', 'green', 'summary'],
}

// === Phase 0: Foundation — shared files touched ONCE for all codes (merge-conflict avoidance) ===
phase('Foundation')
const foundation = await agent(
  `FOUNDATION-FIRST shared-file widening for a parity wave. Touch the SHARED files exactly once for ALL these command codes so the parallel porters that follow never co-widen them (CLAUDE.md: parallel families must be disjoint).
Command codes: ${JSON.stringify(codes)}.
For EACH code:
1. Read its PHP class — legacy/devsam-core/hwe/sammo/Command/{General,Nation}/<code>.php — for scope + signature.
2. Create a COMPILING STUB at logic/src/main/kotlin/opensamguk/logic/actions/<scope>/<CheXxx>.kt mirroring a sibling's class shape, with resolve() left as TODO(포팅). Do NOT implement behavior — the per-command porter fills it.
3. Register the code in logic/.../actions/CommandRegistry.kt (when-arm + import).
4. Add it to intakeCodes + toCommand in app/game-api/.../reserve/CommandWireMapper.kt; add a wire variant in common/wire/TurnDaemonCommand.kt if absent.
After all codes: confirm :logic:compileKotlin + :logic:compileTestKotlin BUILD SUCCESSFUL (gradle via ctx_execute, Java 21, verify by output tail not exit code). Return which files you widened + the stub class names. Do NOT run any gate. Do NOT fill any resolve().`,
  { label: 'foundation', phase: 'Foundation', agentType: 'parity-porter' }
)

// === Phase 1: per-command close, pipelined (each command's files are disjoint) ===
const results = await pipeline(
  codes,

  // stage 1 — capture golden (per-command disjoint fixture)
  (code) =>
    agent(
      `Capture the real PHP golden for command ${code} via tools/php-golden (Docker, scenario_1010). If the command is RNG-free, STATE WHY (quote the PHP showing no rand/RandUtil/pickOne) and skip the fixture; else write logic/src/test/resources/golden/<area>/${code}-fixtures.json and prove two-run sha256-identical. Faithful, never fabricate — uncapturable ⇒ quarantine-with-proof. Return the fixture path or the RNG-free justification.`,
      { label: `golden:${code}`, phase: 'Close', agentType: 'golden-capturer' },
    ).then((golden) => ({ code, golden })),

  // stage 2 — fill the already-registered stub (touch ONLY this command's own files)
  (prev) =>
    agent(
      `FILL the already-registered stub for ${prev.code} (foundation created + registered it). Port resolve() draw-for-draw faithfully and write the *GoldenTest replaying the committed fixture. Touch ONLY logic/src/main/kotlin/.../actions/<scope>/<Che>.kt and logic/src/test/kotlin/.../golden/Che<한글>GoldenTest.kt — do NOT edit CommandRegistry or CommandWireMapper (foundation owns those). Golden context: ${JSON.stringify(prev.golden).slice(0, 600)}. Return the files written.`,
      { label: `port:${prev.code}`, phase: 'Close', agentType: 'parity-porter' },
    ).then((port) => ({ ...prev, port })),

  // stage 3 — gate draw-for-draw; report first divergence on RED (never weaken test / edit golden)
  (prev) =>
    agent(
      `Run the golden gate for ${prev.code}: JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests '*GoldenTest' --rerun-tasks via ctx_execute (host redirects gradle). Verify by test XML (logic/build/test-results/test/*.xml), NOT exit code. If RED, report the FIRST divergence precisely (draw index / expected-vs-actual / first byte-differing log line) so the porter can fix the Kotlin. Do NOT weaken the test or edit the golden. Return {code, green, drawsAsserted, summary, quarantine}.`,
      { label: `gate:${prev.code}`, phase: 'Close', schema: GATE, agentType: 'parity-gate-runner' },
    ).then((gate) => ({ ...prev, gate })),
)

const closed = results.filter(Boolean)
const green = closed.filter((r) => r.gate && r.gate.green)
const red = closed.filter((r) => !r.gate || !r.gate.green)
log(`gates: ${green.length}/${closed.length} green${red.length ? ` — RED: ${red.map((r) => r.code).join(', ')}` : ''}`)

// === Phase 2: integrate — FE submit-wiring (sequential: web pages share files) + one review pass ===
phase('Integrate')
for (const r of green) {
  await agent(
    `Wire the FE submit path for ${r.code} in web/game (form / CommandModal for arg-bearing → Next route handler → game-api intake). Match existing action-page style; httpOnly-cookie auth unchanged. Return what you wired.`,
    { label: `fe:${r.code}`, phase: 'Integrate', agentType: 'fe-submit-wirer' },
  )
}

const review = await agent(
  `Adversarial parity review of this whole wave's diff (commands: ${green.map((r) => r.code).join(', ')}). Check RNG draw order/count/args, PhpRound (half-away), log byte-parity (Josa + markup + execution order), flush-delta-not-inline, intakeCodes presence, one-daemon-write-rule, insertion-order (LinkedHashMap). Use code-review-graph detect_changes/get_impact_radius before Grep. One finding per line, severity-tagged, path:line. List BLOCKERS first.`,
  { label: 'review', phase: 'Integrate', agentType: 'parity-reviewer' },
)

return {
  foundation,
  green: green.map((r) => r.code),
  red: red.map((r) => ({ code: r.code, why: r.gate?.summary || 'no gate result' })),
  review,
  next: red.length
    ? `Fix RED gates (${red.map((r) => r.code).join(', ')}) then re-run parity-wave (resume cached green); do NOT /parity-ship until all green.`
    : 'All green — hand the batch to /parity-ship.',
}
