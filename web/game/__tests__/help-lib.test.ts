import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { HELP_INDEX } from '../lib/help-index';
import { __resetHelpCache, helpApi, helpErrorKind, searchQuery } from '../lib/help';
import { APPROVED_RENAMES, HANJA_READINGS, OLD_WORDS, RENAMED_INPUTS, costValue, helpText, inputName, timingLabel, whoLabel } from '../lib/help-labels';
import { formatHelpView, parseHelpView, type HelpView } from '../lib/help-route';
import { generalActionGroups, helpScreenOf, screenGroups, screenInputIds, type HelpScreen } from '../lib/help-screens';
import { FIRST_STEPS } from '../lib/first-steps';

const ROOT = resolve(__dirname, '../../..');
const read = (p: string) => JSON.parse(readFileSync(resolve(ROOT, p), 'utf-8'));
const catalog: { inputs: { inputId: string; kind: string; timing: { phase: string }; displayName: string | null; helpTopicId: string }[] } =
    read('data/commands/input-catalog.json');
const topics: { topics: { id: string; title: string }[] } = read('data/help/topics.json');

function respond(status: number, body: unknown) {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => __resetHelpCache());
afterEach(() => vi.unstubAllGlobals());

// ── 원장 표류 ──────────────────────────────────────────────────────────────
test('help index matches the input catalog row for row (kind · phase · name)', () => {
    const titles = new Map(topics.topics.map((t) => [t.id, t.title]));
    expect(HELP_INDEX.map((e) => e.inputId)).toEqual(catalog.inputs.map((r) => r.inputId));
    for (const row of catalog.inputs) {
        const e = HELP_INDEX.find((x) => x.inputId === row.inputId)!;
        expect(e.kind).toBe(row.kind);
        expect(e.phase).toBe(row.timing.phase);
        expect(inputName(row.inputId)).toBe(e.name);
        if (!['action.convertProficiency', 'action.tradeGrain'].includes(row.inputId)) {
            expect(e.name).toBe(row.displayName ?? titles.get(row.helpTopicId));
        }
    }
});

test('approved new names replace the old command names, with the particle fitted to the new ending', () => {
    expect(inputName('action.convertProficiency')).toBe('병종 바꿔 익히기');
    expect(inputName('action.tradeGrain')).toBe('쌀 사고팔기');
    expect(helpText('숙련전환을 선택하고 군량매매을 고른다')).toBe('병종 바꿔 익히기를 선택하고 쌀 사고팔기를 고른다');
    expect(helpText('숙련전환이 끝나면')).toBe('병종 바꿔 익히기가 끝나면');
    // 서술격 「이」는 받침 없는 새 이름 뒤에서 줄고(이다 → 다), 받침 있는 말 뒤에서는 남는다. 다른 조사는 뒤에 한글이 와도 조사다.
    expect(helpText('숙련전환이다 · 숙련전환이란 · 군량매매이며')).toBe('병종 바꿔 익히기다 · 병종 바꿔 익히기란 · 쌀 사고팔기며');
    expect(helpText('군이다')).toBe('군이다');
    expect(helpText('숙련전환으로써 · 숙련전환과의 · 숙련전환은커녕')).toBe('병종 바꿔 익히기로써 · 병종 바꿔 익히기와의 · 병종 바꿔 익히기는커녕');
    expect(helpText('인접한 군의 제한된 정보 · 같은 구역 안')).toBe('인접한 군의 제한된 정보 · 같은 구역 안');
    expect(helpText('출사 결과가 확정되면')).toBe('출사 결과가 확정되면');
});

// ── 치환표는 임시다 — 원문(C7)이 고쳐지면 빨개져 표를 지우게 한다 ─────────────────────
const helpSource = readFileSync(resolve(ROOT, 'data/help/topics.json'), 'utf-8') + readFileSync(resolve(ROOT, 'data/help/failure-reasons.json'), 'utf-8');

test.each(OLD_WORDS.map(([from]) => [from]))('help source still carries 「%s」 — otherwise delete that row from help-labels.ts (K7-COPY-06/07)', (from) => {
    expect(helpSource.includes(from), `도움말 원문에 「${from}」이 더 없다 — web/game/lib/help-labels.ts 치환표에서 이 줄을 지워라`).toBe(true);
});

test('the table only holds approved command names and single-meaning hanja readings — no context-dependent words', () => {
    const approved = new Map([['숙련전환', '병종 바꿔 익히기'], ['군량매매', '쌀 사고팔기']]);
    for (const [from, to] of APPROVED_RENAMES) expect(approved.get(from)).toBe(to);
    for (const [from, to] of HANJA_READINGS) {
        expect(from).toMatch(/^[\u4e00-\u9fff]+$/);
        expect(to).toMatch(/^[가-힣]+$/);
    }
    expect(OLD_WORDS.map(([from]) => from)).not.toContain('휘하');
});

test.each(Object.entries(RENAMED_INPUTS))('input %s still has the old catalog name — otherwise delete it from RENAMED_INPUTS', (inputId, name) => {
    const row = catalog.inputs.find((r) => r.inputId === inputId)!;
    const titles = new Map(topics.topics.map((t) => [t.id, t.title]));
    expect(row.displayName ?? titles.get(row.helpTopicId), `원장 · 주제가 이미 「${name}」이다 — RENAMED_INPUTS 에서 지워라`).not.toBe(name);
});

test('every catalog input sits on exactly one screen, and the war room holds every direct action by phase', () => {
    const screens: HelpScreen[] = ['war-room', 'territory', 'court', 'diplomacy', 'stratagem', 'siege', 'retinue', 'corps', 'enlist', 'realm'];
    const nonWar = screens.filter((sc) => sc !== 'war-room').flatMap(screenInputIds);
    const direct = HELP_INDEX.filter((e) => e.kind === 'GENERAL_ACTION').map((e) => e.inputId);
    const stratagems = HELP_INDEX.filter((e) => e.kind === 'STRATAGEM').map((e) => e.inputId);
    expect(new Set(screenInputIds('war-room'))).toEqual(new Set(direct));
    expect(generalActionGroups().map((g) => [g.label, g.entries.length])).toEqual([['현장 행동', 26], ['이동', 5], ['정치', 9], ['공성', 3]]);
    const covered = new Set([...direct, ...nonWar, ...stratagems]);
    expect(catalog.inputs.filter((r) => !covered.has(r.inputId)).map((r) => r.inputId)).toEqual([]);
    expect(screenGroups('other')).toEqual([]);
});

// ── 첫걸음(D21 — 설명만) ─────────────────────────────────────────────────────
test('first steps are the eight approved steps in order: 가입 → 생성 → 출사 → 발령 → 공사 → 등용 → 행군 → 전투', () => {
    expect(FIRST_STEPS.map((st) => st.explanationId)).toEqual([
        'tutorial.signup', 'tutorial.createGeneral', 'tutorial.enlist', 'tutorial.dispatch',
        'tutorial.work', 'tutorial.employ', 'tutorial.march', 'tutorial.battle',
    ]);
    expect(FIRST_STEPS.map((st) => [st.order, st.name])).toEqual([
        [1, '가입'], [2, '장수 생성'], [3, '출사'], [4, '발령'], [5, '공사'], [6, '등용'], [7, '행군'], [8, '전투'],
    ]);
    for (const st of FIRST_STEPS) {
        expect(st.what.length, st.key).toBeGreaterThan(0);
        expect(st.how.length, st.key).toBeGreaterThanOrEqual(1);
        expect(st.how.length, st.key).toBeLessThanOrEqual(3);
    }
    // 아직 없는 것은 지어내지 않고 「준비 중」 — 실시간 전투 참가 · 포로 등용
    expect(FIRST_STEPS.filter((st) => st.pending).map((st) => st.key)).toEqual(['create', 'employ', 'battle']);
});

/** 셸 주소 조각 → 그 화면 페이지 파일(app/game 아래, 캠페인 묶음 포함). 없는 화면 바로가기는 404 다. */
function pageFileFor(slug: string): string | null {
    const path = slug.split('?')[0];
    const candidates = path === '' ? ['app/game/page.tsx'] : [`app/game/${path}/page.tsx`, `app/game/(campaign)/${path}/page.tsx`];
    return candidates.find((c) => existsSync(resolve(ROOT, 'web/game', c))) ?? null;
}

test('every shortcut opens a screen that exists — game pages under app/game, sign-up on the gateway', () => {
    for (const st of FIRST_STEPS) {
        if (st.go.kind === 'game') expect(pageFileFor(st.go.slug), `${st.key} → ${st.go.slug}`).not.toBeNull();
        else {
            expect(st.go.href.endsWith('/join'), st.key).toBe(true);
            expect(existsSync(resolve(ROOT, 'web/gateway/app/join/page.tsx'))).toBe(true);
        }
    }
});

test('battle explanation opens the approved campaign hub and preserves the server waiting contract', () => {
    const step = FIRST_STEPS.find((st) => st.key === 'battle')!;
    expect(step.go).toEqual({ kind: 'game', slug: 'corps/battle', label: '전투 · 부재 대비로' });
    expect(pageFileFor('corps/battle')).toBe('app/game/(campaign)/corps/battle/page.tsx');
    expect(step.pending).toContain('서버가 아직 전투를 열지 않아');
    expect([step.what, step.where, ...step.how].join(' ')).not.toMatch(/감찰부|리플레이|전투 결과/);
});

/** 버튼 이름을 조합해 그리는 곳 — 원문에 문자 그대로 없다(`${이름} 예약`). */
const COMPOSED_LABELS: Record<string, { file: string; marker: string; base: string }> = {
    // 흐름 제출 단추 — `${순 번호}순에 예약`(ArgsPanel). 첫걸음 글은 「NN순에 예약」(NN = 순 번호)으로 쓴다.
    'NN순에 예약': { file: 'web/game/components/command-flow/ArgsPanel.tsx', marker: '}순에 예약`', base: '순에 예약' },
};

/**
 * 단계마다 그 화면을 그리는 소스만 본다 — 저장소 어디엔가 남은 옛 부품(예: 조정 P-K01 뒤에도 작전실 명령 창에 남은 옛 발령 칸)의
 * 글자로 통과하지 않게. 화면을 바꾸면 이 표와 첫걸음 문장을 같이 고친다.
 * 한계: 소스에 글자가 있어도 조건부로 안 그려질 수 있다(예: 「지도에서 고르기」는 onMapPick 이 붙어야 보인다 — K6 대조로 찾음).
 * 그려지는지는 첫걸음 e2e(help.spec)가 마지막으로 본다.
 */
const STEP_SOURCES: Record<string, readonly string[]> = {
    register: ['web/gateway/app', 'web/gateway/components'],
    create: ['web/game/components/entry', 'web/game/app/game/create', 'web/gateway/components/lobby'],
    // 작전실 명령 흐름(K6 #1125): 흐름 패널 · 12순 칸 · 명령 이름(catalog) · 인자 칸 이름(options)
    enlist: ['web/game/components/enlist', 'web/game/app/game/join'],
    employ: ['web/game/components/command-flow', 'web/game/components/turn-slots', 'web/game/lib/command-flow'],
    march: ['web/game/components/command-flow', 'web/game/components/turn-slots', 'web/game/lib/command-flow'],
    dispatch: ['web/game/components/court', 'web/game/components/requests'],
    work: ['web/game/components/territory', 'web/game/app/game/(campaign)/territory'],
    battle: ['web/game/components/battle', 'web/game/lib/battle', 'web/game/app/game/(campaign)/corps/battle'],
};

/** 경로(파일 · 폴더) 아래 .ts · .tsx 원문을 한데 모은다(시험 파일 제외). 단계마다 한 번 읽는다. */
const sourceCache = new Map<string, string>();
function sourceText(paths: readonly string[]): string {
    const key = paths.join('|');
    const hit = sourceCache.get(key);
    if (hit !== undefined) return hit;
    const out: string[] = [];
    const walk = (abs: string) => {
        if (!existsSync(abs)) return;
        if (statSync(abs).isDirectory()) {
            for (const name of readdirSync(abs)) if (name !== '__tests__' && name !== 'node_modules') walk(resolve(abs, name));
        } else if (/\.tsx?$/.test(abs) && !/\.test\.tsx?$/.test(abs)) out.push(readFileSync(abs, 'utf-8'));
    };
    for (const p of paths) walk(resolve(ROOT, p));
    const text = out.join('\n');
    sourceCache.set(key, text);
    return text;
}

test('every step source path exists — a deleted screen file must turn the label guard red, not be skipped', () => {
    // sourceText 는 없는 경로를 건너뛴다. 2026-10-02 main: 지운 DomesticPanels.tsx 를 들고도 가드가 초록이었다(#1174 · #1146 → #1200).
    const missingPaths = Object.entries(STEP_SOURCES).flatMap(([key, paths]) => paths.filter((p) => !existsSync(resolve(ROOT, p))).map((p) => `${key}: ${p}`));
    expect(missingPaths).toEqual([]);
});

test('every quoted control name in 「어디서」·「어떻게」 exists in that step\'s own screen sources (no invented or stale labels)', () => {
    expect(Object.keys(STEP_SOURCES).sort()).toEqual(FIRST_STEPS.map((st) => st.key).sort());
    let count = 0;
    const missing: string[] = [];
    for (const st of FIRST_STEPS) {
        const labels = [st.where, ...st.how].flatMap((line) => [...line.matchAll(/「([^」]+)」/g)].map((m) => m[1]));
        count += labels.length;
        for (const label of labels) {
            const composed = COMPOSED_LABELS[label];
            if (composed) {
                const src = readFileSync(resolve(ROOT, composed.file), 'utf-8');
                if (!src.includes(composed.marker) || !src.includes(composed.base)) missing.push(`${st.key}: ${label}`);
                continue;
            }
            if (!sourceText(STEP_SOURCES[st.key]).includes(label)) missing.push(`${st.key}: ${label}`);
        }
    }
    expect(count).toBeGreaterThan(15);
    expect(missing).toEqual([]);
});

// ── 오류 · 캐시 · 검색 ──────────────────────────────────────────────────────
test.each([
    [404, 'HELP_TOPIC_NOT_FOUND', 'NOT_FOUND'],
    [404, 'WORLD_PROFILE_UNAVAILABLE', 'PROFILE_UNAVAILABLE'],
    [503, 'WORLD_UNAVAILABLE', 'WORLD_UNAVAILABLE'],
    [400, 'INVALID_SEARCH_QUERY', 'BAD_QUERY'],
    [401, 'AUTH_REQUIRED', 'AUTH'],
    [500, null, 'OTHER'],
])('status %i · %s keeps its kind (%s)', async (status, code, kind) => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(status, code ? { error: { code, message: '서버 문장' } } : {})));
    const err = await helpApi.topic('commands.action.enlist').catch((e: unknown) => e);
    expect(helpErrorKind(err)).toBe(kind);
    expect((err as { code: string | null }).code).toBe(code);
});

test('network failure is its own kind', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('offline'); }));
    expect(helpErrorKind(await helpApi.context('action.enlist').catch((e: unknown) => e))).toBe('NETWORK');
});

test('static reads are cached per tab; failures are not', async () => {
    const fetchMock = vi.fn(async (_url: string) => respond(200, { schemaVersion: 1, topic: { id: 't' } }));
    vi.stubGlobal('fetch', fetchMock);
    await helpApi.topic('t');
    await helpApi.topic('t');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/game/api/help/topics/t');

    const flaky = vi.fn().mockResolvedValueOnce(respond(503, { error: { code: 'WORLD_UNAVAILABLE', message: '' } }))
        .mockResolvedValueOnce(respond(200, { schemaVersion: 1, reason: 'X' }));
    vi.stubGlobal('fetch', flaky);
    await expect(helpApi.failure('X', 'action.enlist')).rejects.toThrow();
    await expect(helpApi.failure('X', 'action.enlist')).resolves.toMatchObject({ reason: 'X' });
    expect(flaky.mock.calls.map((c) => c[0])).toEqual(['/api/game/api/help/failures/X?inputId=action.enlist', '/api/game/api/help/failures/X?inputId=action.enlist']);
});

test('search query must be 2–80 characters after trimming (server 400 is prevented)', () => {
    expect(searchQuery(' 화 ')).toBeNull();
    expect(searchQuery(' 화계 ')).toBe('화계');
    expect(searchQuery('가'.repeat(80))).toHaveLength(80);
    expect(searchQuery('가'.repeat(81))).toBeNull();
});

// ── 주소 ───────────────────────────────────────────────────────────────────
test.each<HelpView>([
    { kind: 'home' }, { kind: 'browse' }, { kind: 'start' }, { kind: 'search', q: '출사' },
    { kind: 'input', inputId: 'action.enlist' }, { kind: 'input', inputId: 'action.employ', reason: 'BATTLE_PENDING' },
    { kind: 'topic', topicId: 'concepts.createGeneral' }, { kind: 'failure', reason: 'NOT_LORD', inputId: 'court.dispatch' },
])('help view %j round-trips through the address', (view) => {
    expect(parseHelpView(formatHelpView(view))).toEqual(view);
});

test.each(['', 'nope', 'input:<script>', 'failure:A B', 'topic:'])('bad help param %j opens nothing', (value) => {
    expect(parseHelpView(value)).toBeNull();
});

// ── 화면 말 ─────────────────────────────────────────────────────────────────
test('cost: 0 = 들지 않음, number = value, null = 상황에 따라 (never free)', () => {
    expect(costValue({ money: 0 }, 'money')).toBe('들지 않음');
    expect(costValue({ money: 100 }, 'money')).toBe('100');
    expect(costValue({ grain: null }, 'grain')).toBe('상황에 따라');
    expect(costValue({}, 'iron')).toBe('상황에 따라');
});

test('codes become plain words; unknown codes are dropped, not invented', () => {
    expect(whoLabel({ actor: 'GENERAL', authorityRule: 'SUBJECT_OWNER' })).toBe('장수 본인 · 내 장수');
    expect(whoLabel({ actor: 'ALIEN', authorityRule: 'DECISION_AUTHORITY' })).toBe('결정권자');
    expect(timingLabel({ phase: 'POLITICS', turnSlots: 12, perPhaseLimit: 1 })).toBe('명령 목록 12순 · 한 순에 하나 · 정치 단계');
    expect(timingLabel({ phase: 'CARD_TRIGGER' })).toBe('카드 조건이 맞을 때');
    expect(timingLabel({ phase: 'NEW_PHASE' })).toBe('');
});

test('셸 위치 → 「이 화면」: 묶음 · 화면 경로에서 고르고, 목록 없는 묶음은 other', () => {
    expect(helpScreenOf('war', '')).toBe('war-room');
    expect(helpScreenOf('retinue', 'retinue/yuedan')).toBe('retinue');
    expect(helpScreenOf('corps', 'corps/siege')).toBe('siege');
    expect(helpScreenOf('corps', 'corps/battle')).toBe('corps');
    expect(helpScreenOf('court', 'court?tab=orders')).toBe('court');
    expect(helpScreenOf('court', 'court/diplomacy')).toBe('diplomacy');
    expect(helpScreenOf('court', 'court/realm')).toBe('realm');
    expect(helpScreenOf('records', 'records')).toBe('other');
    expect(helpScreenOf(null, null)).toBe('other');
    expect(screenGroups('other')).toEqual([]);
    // 10-02 새 화면 — 현 상세 · 창고망 · 시야첩보는 그 화면 단추만 따로 보인다.
    expect(helpScreenOf('territory', 'territory/county')).toBe('county');
    expect(helpScreenOf('territory', 'territory/county/30')).toBe('county');
    expect(helpScreenOf('territory', 'territory/supply')).toBe('supply');
    expect(helpScreenOf('territory', 'territory')).toBe('territory');
    expect(helpScreenOf('corps', 'corps/intel')).toBe('intel');
    expect(screenInputIds('county')).toEqual(['placement.assign', 'policy.set', 'work.start', 'action.scout']);
    expect(screenInputIds('supply')).toEqual(['action.transport']);
    expect(screenInputIds('intel')).toEqual(['action.scout']);
});

/** 게임 화면 소스에서 `<InputAction …>` 여는 태그를 꺼낸다(속성 안 `{ … }` 의 `>` · `=>` 를 건너뛴다). */
function inputActionTags(text: string): { line: number; tag: string }[] {
    const out: { line: number; tag: string }[] = [];
    const re = /<InputAction\b/g;
    for (let m = re.exec(text); m; m = re.exec(text)) {
        let depth = 0;
        let quote: string | null = null; // 따옴표 안의 「{ } >」 는 글자다(CodeRabbit #1259)
        let i = m.index + m[0].length;
        for (; i < text.length; i += 1) {
            const c = text[i];
            if (quote) {
                if (c === '\\') i += 1;
                else if (c === quote) quote = null;
                continue;
            }
            if (c === '"' || c === "'" || c === '`') quote = c;
            else if (c === '{') depth += 1;
            else if (c === '}') depth -= 1;
            else if (c === '>' && depth === 0) break;
        }
        out.push({ line: text.slice(0, m.index).split('\n').length, tag: text.slice(m.index, i + 1) });
    }
    return out;
}

test('the InputAction tag scanner reads quoted attribute text as text — a "}" or ">" in a value neither swallows nor cuts the next tag', () => {
    // CodeRabbit #1259: 따옴표 안 「}」 가 깊이를 음수로 만들어 첫 태그가 뒤 태그의 helpTopic 까지 삼키면, 도움말 없는 단추를 놓친다.
    const src = '<InputAction aria-label="}" reasonTitle="a > b" />\n<InputAction helpTopic={t} />';
    const tags = inputActionTags(src);
    expect(tags.map((t) => t.line)).toEqual([1, 2]);
    expect(tags[0].tag).toBe('<InputAction aria-label="}" reasonTitle="a > b" />');
    expect(tags[1].tag).toBe('<InputAction helpTopic={t} />');
});

test('every InputAction on a game screen carries the help link — reason sheet → 「도움말 — …」 (HelpedInputAction or a spread help)', () => {
    // 2026-10-03: 새로 병합된 조정 · 외교 · 부 · 받은 요청 · 계책 덱의 결정 단추 11개가 맨 InputAction 이라 막힌 사유에 도움말 고리가 없었다.
    const bare: string[] = [];
    const walk = (abs: string) => {
        for (const name of readdirSync(abs)) {
            const full = resolve(abs, name);
            if (statSync(full).isDirectory()) { if (name !== '__tests__') walk(full); continue; }
            if (!/\.tsx$/.test(name) || /\.test\.tsx$/.test(name) || name === 'HelpedInputAction.tsx') continue;
            for (const { line, tag } of inputActionTags(readFileSync(full, 'utf-8'))) {
                if (!/\{\.\.\.\w*[Hh]elp\w*\}|helpTopic=|onHelp=/.test(tag)) bare.push(`${full.slice(ROOT.length + 1)}:${line}`);
            }
        }
    };
    walk(resolve(ROOT, 'web/game/components'));
    expect(bare).toEqual([]);
});

test('계책 화면은 계책 입력 13개 전부(설계서 §6 — P-S01 계책 덱)', () => {
    const ids = screenInputIds('stratagem');
    expect(ids).toHaveLength(13);
    expect(ids.every((id) => id.startsWith('stratagem.'))).toBe(true);
});
