// one-shot golden generator — josa + log TS oracle (devsam-core2026)
//
// Imports the reference TypeScript modules (JosaUtil, formatLogText, convertLog)
// and emits committed JSON golden fixtures that the Kotlin JosaLogGoldenTest
// asserts against byte-for-byte. Oracle = TS devsam-core2026 (the parity target),
// NOT PHP. Run ONCE, commit the produced JSON, regenerate ONLY when the TS
// modules change. Never executed in CI.
//
// Prerequisite (one-shot host step): from legacy/devsam-core2026 run `pnpm install`.
// Run: npx tsx /Users/apple/Desktop/개인프로젝트/opensamguk/tools/golden-dump/josa-log-golden.mjs
// (paths below are absolute, so the script may be invoked from anywhere).

import { writeFileSync, mkdirSync } from 'node:fs';

// Reference oracle TS lives in the MAIN repo's legacy/ checkout.
const TS = '/Users/apple/Desktop/개인프로젝트/opensamguk/legacy/devsam-core2026';
const { JosaUtil } = await import(`${TS}/packages/common/src/util/JosaUtil.ts`);
const { formatLogText } = await import(`${TS}/packages/logic/src/logging/formatter.ts`);
const { convertLog } = await import(`${TS}/app/game-api/src/battleSim/logFormatter.ts`);

// Fixtures land in THIS worktree's test resources (absolute, unambiguous).
const base = '/Users/apple/Desktop/개인프로젝트/p0b-wt-a/common/src/test/resources/golden';
mkdirSync(`${base}/josa`, { recursive: true });
mkdirSync(`${base}/log`, { recursive: true });

// ── josa/pick.json ─────────────────────────────────────────────────────────
// Covers every hasJongsung branch via pick: Hangul ±jongsung, ㄹ+isRo, compat
// jamo, digits 0-9, latin vowel/consonant, in-range hanja U+4E00/U+5ED3,
// out-of-range U+5ED4, trailing punct/space, empty/null, all josa families +
// normalization equivalence + explicit-woJongsung bypass.
const pickCases = [
    { text: '한국', josa: '은' }, { text: '사과', josa: '은' }, { text: '서울', josa: '으로' }, { text: '한국', josa: '으로' },
    { text: 'ㄱ', josa: '은' }, { text: 'ㄹ', josa: '으로' }, { text: '100', josa: '은' }, { text: '12', josa: '은' }, { text: '1', josa: '으로' },
    { text: 'Kim', josa: '은' }, { text: 'Lee', josa: '은' }, { text: '一', josa: '은' }, { text: '廓', josa: '은' }, { text: '국廔', josa: '은' },
    { text: '한국!! ', josa: '은' }, { text: '', josa: '은' }, { text: null, josa: '은' }, { text: '한국', josa: '는' }, { text: '한국', josa: '(은)는' },
];
const pick = pickCases.map((c) => ({ ...c, expected: JosaUtil.pick(c.text, c.josa) }));
let buteo;
try {
    JosaUtil.pick('진', '부터');
    buteo = { throws: false };
} catch (e) {
    buteo = { throws: true, message: e.message };
}
pick.push({ text: '진', josa: '부터', ...buteo });
writeFileSync(`${base}/josa/pick.json`, JSON.stringify(pick, null, 2));

// ── log/convertLog.json ──────────────────────────────────────────────────────
// Every tag incl nested <D><b>..</b></>, <Y1>-before-<Y> ordering trap,
// <b>/<br> passthrough, type 1/0/-1, the <D>==<O>==orangered duplicate.
const convCases = ['<C>x</>', '<1>x</>', '<Y1>x</>', '<Y>x</>', '<D>x</>', '<O>x</>', '<D><b>x</b></>', 'a<b>b</b><br>c', '<C>x</>'];
const conv = convCases.map((v) => ({ value: v, type: 1, expected: convertLog(v, 1) }));
conv.push({ value: '<C>x</>', type: 0, expected: convertLog('<C>x</>', 0) });
conv.push({ value: '<C>x</>', type: -1, expected: convertLog('<C>x</>', -1) });
writeFileSync(`${base}/log/convertLog.json`, JSON.stringify(conv, null, 2));

// ── log/formatLogText.json ───────────────────────────────────────────────────
// One case per LogFormat 0-8 at year=190 month=3 (the RAW baked markup
// finalizeLogEntry stores, pre-convertLog).
const fmt = [];
for (let f = 0; f <= 8; f++) fmt.push({ format: f, expected: formatLogText('본문', f, 190, 3) });
writeFileSync(`${base}/log/formatLogText.json`, JSON.stringify(fmt, null, 2));

// ── log/render-e2e.json ──────────────────────────────────────────────────────
// convertLog(formatLogText(...)) = the FINAL display html, post-convertLog.
const e2e = ['투자', `${JosaUtil.put('한국', '은')} 성공`, '<C>점수</> 상승'].map((raw) => ({
    raw,
    format: 2,
    expected: convertLog(formatLogText(raw, 2, 190, 3)),
}));
writeFileSync(`${base}/log/render-e2e.json`, JSON.stringify(e2e, null, 2));

console.log('golden written');
