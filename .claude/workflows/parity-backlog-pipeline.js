export const meta = {
  name: 'parity-backlog-pipeline',
  description: 'Fine-grained backlog execution: 15 areas x 3 stages (research/implement/verify) via pipeline for max concurrency',
  phases: [
    { title: 'Research', detail: '15 agents parallel: each area researches its scope' },
    { title: 'Implement', detail: '15 agents parallel: each area implements based on research' },
    { title: 'Verify', detail: '15 agents parallel: compile/test/review per area' },
  ],
}

const AREAS = [
  { id: 'b1-log-golden', desc: 'B1 MakeGeneral log byte-match PHP golden capture + fix + gate' },
  { id: 'fe-layout-main', desc: 'FE main page.tsx multi-column grid (map-left + turn-table-right + log + cards)' },
  { id: 'fe-turn-table', desc: 'FE turn table component (reserved commands, turn index display)' },
  { id: 'fe-log-panel', desc: 'FE log panel component (global/general/nation action logs)' },
  { id: 'fe-map-expand', desc: 'FE map area expansion (remove 1000px cap, full-width responsive)' },
  { id: 'fe-city-card', desc: 'FE city card enrichment (officerList, region, level, population gauges)' },
  { id: 'fe-general-card', desc: 'FE general card enrichment (full stats, equipment, personality display)' },
  { id: 'fe-nation-card', desc: 'FE nation card enrichment (diplomacy, finance, policy display)' },
  { id: 'fe-card-grid', desc: 'FE card grid layout (general/nation/city cards positioning)' },
  { id: 'fe-shell-update', desc: 'FE Shell component update (layout wrapper, nav, responsive)' },
  { id: 'dispatcher-gaps', desc: 'TurnDaemonCommandDispatcher missing handler gaps (list + stub)' },
  { id: 'wire-gaps', desc: 'CommandWireMapper missing intake codes (list + toCommand stubs)' },
  { id: 'controller-gaps', desc: 'Missing game-api controllers for unported commands' },
  { id: 'audit-legacy', desc: 'Legacy PHP vs Kotlin gap audit (behavioral divergences)' },
  { id: 'audit-hardcode', desc: 'Hardcoded values inventory (web/app static values that should be API-driven)' },
]

log(`Pipeline over ${AREAS.length} areas: ${AREAS.map(a => a.id).join(', ')}`)

// Stage 1: Research — all 15 agents in parallel
phase('Research')
const researchResults = await pipeline(
  AREAS,
  (area) => agent(
    `RESEARCH: ${area.desc}. Read relevant source files and PHP legacy references. ` +
    `Identify exactly what is missing, what files need changing, and what the PHP grand truth looks like. ` +
    `Return: specific file paths, line numbers, PHP reference locations, and a concrete todo list.`,
    { label: `research-${area.id}` }
  )
)

log(`Research complete. ${researchResults.filter(Boolean).length}/${AREAS.length} areas researched.`)

// Stage 2: Implement — all 15 agents in parallel (each gets its own research result)
phase('Implement')
const implementResults = await pipeline(
  AREAS.map((area, idx) => ({ area, research: researchResults[idx] })),
  ({ area, research }) => agent(
    `IMPLEMENT: ${area.desc}. Based on research: ${JSON.stringify(research).slice(0, 500)}. ` +
    `Make concrete code changes. Follow existing patterns. Do NOT fabricate PHP behavior. ` +
    `Return: list of modified files with change summaries.`,
    { label: `impl-${area.id}` }
  )
)

log(`Implementation complete. ${implementResults.filter(Boolean).length}/${AREAS.length} areas implemented.`)

// Stage 3: Verify — all 15 agents in parallel
phase('Verify')
const verifyResults = await pipeline(
  AREAS.map((area, idx) => ({ area, impl: implementResults[idx] })),
  ({ area, impl }) => agent(
    `VERIFY: ${area.desc}. Changes: ${JSON.stringify(impl).slice(0, 300)}. ` +
    `Run relevant compile/test checks. For FE: tsc --noEmit. For Kotlin: ./gradlew :module:test. ` +
    `Flag any errors, dead code, or fabricated behavior. Return: PASS or FAIL with reasons.`,
    { label: `verify-${area.id}` }
  )
)

log(`Verification complete.`)

return {
  areas: AREAS.length,
  research: researchResults.filter(Boolean).length,
  implemented: implementResults.filter(Boolean).length,
  verified: verifyResults.filter(Boolean).length,
  details: AREAS.map((a, i) => ({
    id: a.id,
    research: !!researchResults[i],
    implemented: !!implementResults[i],
    verified: !!verifyResults[i],
  })),
}
