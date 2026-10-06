// Front layer rules of ADR-LITE-070 (docs/development/agent-reference.md, 「층과 의존 방향」).
// tools/ci/depcruise_counts.py runs this once per app (game · gateway · shared, each with its own tsconfig so `@/` resolves)
// and ratchets the violation count per rule — it does not fail on the existing violations.
// Screen/page → api client imports and raw fetch are counted by tools/ci/arch_lint.py (f1 · f2), not here.
// Paths are relative to web/. Type-only imports are compiled away (tsPreCompilationDeps: false): only value imports count.

const TESTS = '(^|/)(__tests__|e2e)/|\\.(test|spec)\\.tsx?$';
const API_CLIENT = '^(game|gateway)/lib/(api\\.tsx?$|api/|server-api\\.tsx?$|requests\\.tsx?$|mailbox\\.tsx?$|.*-reads\\.ts$)';
// two alternatives instead of `lib/(.+/)?use-`: dependency-cruiser rejects nested quantifiers (safe-regex).
const HOOK = '^(game|gateway)/hooks/|^(game|gateway)/lib/use-[^/]+\\.tsx?$|^(game|gateway)/lib/.+/use-[^/]+\\.tsx?$';

/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: 'no-circular',
      comment: 'Value-import file cycles (target 0).',
      severity: 'error',
      from: { pathNot: TESTS },
      to: { circular: true },
    },
    {
      name: 'shared-not-to-apps',
      comment: '@opensamguk/ui must not import web/game or web/gateway.',
      severity: 'error',
      from: { path: '^shared/' },
      to: { path: '^(game|gateway)/' },
    },
    {
      name: 'no-cross-app',
      comment: 'web/game and web/gateway do not import each other (tests may share e2e support).',
      severity: 'error',
      from: { path: '^(game|gateway)/', pathNot: TESTS },
      to: { path: '^(game|gateway)/', pathNot: '^$1/' },
    },
    {
      name: 'lib-not-to-components',
      comment: 'lib (view models · api clients · utilities) must not import components (reverse dependency).',
      severity: 'error',
      from: { path: '^(game|gateway)/lib/', pathNot: TESTS },
      to: { path: '^$1/components/' },
    },
    {
      name: 'lib-not-to-hooks',
      comment: 'Non-hook lib modules must not import hooks (reverse dependency).',
      severity: 'error',
      from: { path: '^(game|gateway)/lib/', pathNot: `${HOOK}|${TESTS}` },
      to: { path: HOOK },
    },
    {
      name: 'hooks-not-to-components',
      comment: 'Hooks return data, not JSX components.',
      severity: 'error',
      from: { path: HOOK, pathNot: TESTS },
      to: { path: '^(game|gateway)/components/' },
    },
    {
      name: 'view-not-to-api',
      comment: 'View models (*-view.ts) are pure: no api client.',
      severity: 'error',
      from: { path: '-view\\.ts$', pathNot: TESTS },
      to: { path: API_CLIENT },
    },
    {
      name: 'parts-not-to-api',
      comment: 'Display parts (*Parts.tsx) take props: no api client.',
      severity: 'error',
      from: { path: 'Parts\\.tsx$', pathNot: TESTS },
      to: { path: API_CLIENT },
    },
    {
      name: 'client-not-to-route-handlers',
      comment: 'Server route handlers (app/api/**/route.ts) are the BFF; client code does not import them.',
      severity: 'error',
      from: { pathNot: `/app/api/|${TESTS}` },
      to: { path: '^(game|gateway)/app/api/.+/route\\.ts$' },
    },
  ],
  options: {
    doNotFollow: { path: 'node_modules' },
    exclude: { path: '(^|/)(node_modules|\\.next|coverage|test-results|playwright-report|public)/' },
    tsPreCompilationDeps: false,
    skipAnalysisNotInRules: true,
    enhancedResolveOptions: {
      exportsFields: ['exports'],
      conditionNames: ['import', 'require', 'node', 'default', 'types'],
      extensions: ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.json'],
      mainFields: ['module', 'main', 'types', 'typings'],
    },
  },
};
