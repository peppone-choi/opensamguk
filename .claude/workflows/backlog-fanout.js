export const meta = {
  name: 'opensamguk-backlog-fanout',
  description: 'Execute full backlog with concurrent agent fan-out',
  phases: [
    { title: 'Research', detail: '4 agents parallel: handoff + gap + fe-state + php-layout' },
    { title: 'Implement', detail: '5 agents parallel: B1 golden + FE layout + city card + general card + audit' },
    { title: 'Verify', detail: '3 agents parallel: compile + tests + adversarial review' },
  ],
}

// Phase 1 -- Research (4 agents parallel)
phase('Research')
log('Spawning 4 research agents in parallel...')
const [handoff, gapAudit, feState, phpLayout] = await parallel([
  () => agent(
    'Read docs/superpowers/SESSION_HANDOFF.md thoroughly. Extract ALL remaining tasks/backlog items with priorities. Focus on: B1-completion items, FE layout, FE cards, FE misc, audit tasks. Return structured bullet list with [P0/P1/P2] tags.',
    { label: 'research-handoff' }
  ),
  () => agent(
    'Read docs/superpowers/GAP_AUDIT.md and all gap/ sub-files. List every open gap (missing commands, missing APIs, missing FE pages, behavioral divergences). Return structured list with severity (critical/high/medium).',
    { label: 'research-gap' }
  ),
  () => agent(
    'Analyze web/game current state. Read app/game/page.tsx, components/Shell, and key layout files. Identify: current layout structure, what is missing vs PHP v_main (multi-column, turn table, log panel, card positions). Return specific file paths + gap descriptions.',
    { label: 'research-fe-state' }
  ),
  () => agent(
    'Read legacy/devsam-core PHP files for v_main layout reference: hwe/v_main.php, hwe/ts/PageMain.vue or equivalent. Extract: column structure, component order, CSS/layout rules, turn table position, log panel position, card grid. Return exact layout spec.',
    { label: 'research-php-layout' }
])

log('Research complete.')

// Phase 2 -- Implementation (5 agents parallel)
phase('Implement')
log('Spawning 5 implementation agents in parallel...')
const [b1Golden, feLayout, feCityCard, feGeneralCard, audit] = await parallel([
  () => agent(
    'B1 MakeGeneral log golden gate. Read MakeGeneralHandler.kt current log strings. Compare to PHP Join.php:502-528. Fix handler log strings to byte-match PHP exactly. Run :logic:test. Return: modified files, PASS/FAIL.',
    { label: 'impl-b1-golden' }
  ),
  () => agent(
    'FE main layout restructure. Read web/game/app/game/page.tsx. Apply PHP v_main multi-column layout: map-left + turn-table-right + log panel + cards grid. Remove 1000px cap. Ensure tsc passes. Return: modified files.',
    { label: 'impl-fe-layout' }
  ),
  () => agent(
    'FE city card enrichment. Find city card components. Compare to PHP CityBasicCard.vue. Add missing: officer list, region name, city level. Ensure tsc passes. Return: modified files.',
    { label: 'impl-fe-city' }
  ),
  () => agent(
    'FE general/nation card enrichment. Find general/nation card components. Compare to PHP full cards. Add missing fields. Ensure tsc passes. Return: modified files.',
    { label: 'impl-fe-cards' }
  ),
  () => agent(
    'Audit: legacy gap + hardcode inventory. Scan web/game and app/ for hardcoded values. Compare devsam PHP to Kotlin. Write findings to docs/superpowers/gap/. Return: gaps found, files written.',
    { label: 'impl-audit' }
])

log('Implementation complete.')

// Phase 3 -- Verification (3 agents parallel)
phase('Verify')
log('Spawning 3 verification agents in parallel...')
const [compileOk, testsOk, reviewOk] = await parallel([
  () => agent(
    'Full compile check. Run gradle compileKotlin for all modules + tsc --noEmit in web/game. Return: BUILD SUCCESSFUL or first error.',
    { label: 'verify-compile' }
  ),
  () => agent(
    'Run test suite. Execute gradle test for common/logic/game-engine/game-api. Report pass counts and failures.',
    { label: 'verify-tests' }
  ),
  () => agent(
    'Adversarial review of all changes. Check B1 log fidelity, FE layout correctness, auth guards, no dead code. Return findings or CLEAN.',
    { label: 'verify-review' }
])

return {
  research: { handoff: !!handoff, gapAudit: !!gapAudit, feState: !!feState, phpLayout: !!phpLayout },
  implementation: { b1Golden: !!b1Golden, feLayout: !!feLayout, feCityCard: !!feCityCard, feGeneralCard: !!feGeneralCard, audit: !!audit },
  verification: { compile: !!compileOk, tests: !!testsOk, review: !!reviewOk },
}