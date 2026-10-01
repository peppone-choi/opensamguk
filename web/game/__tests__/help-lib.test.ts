import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { HELP_INDEX } from '../lib/help-index';
import { __resetHelpCache, helpApi, helpErrorKind, searchQuery } from '../lib/help';
import { APPROVED_RENAMES, HANJA_READINGS, OLD_WORDS, RENAMED_INPUTS, costValue, helpText, inputName, timingLabel, whoLabel } from '../lib/help-labels';
import { formatHelpView, parseHelpView, type HelpView } from '../lib/help-route';
import { generalActionGroups, helpScreenOf, screenGroups, screenInputIds, type HelpScreen } from '../lib/help-screens';
import { TUTORIAL_STEPS } from '../lib/tutorial-steps';

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

test('tutorial steps follow the contract fixture ids and order', () => {
    const fixture = read('docs/development/fixtures/help-tutorial/tutorial-progress-start.json');
    expect(TUTORIAL_STEPS.map((s) => [s.id, s.order])).toEqual(fixture.objectives.map((o: { id: string; order: number }) => [o.id, o.order]));
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
});

test('계책 화면은 계책 입력 13개 전부(설계서 §6 — P-S01 계책 덱)', () => {
    const ids = screenInputIds('stratagem');
    expect(ids).toHaveLength(13);
    expect(ids.every((id) => id.startsWith('stratagem.'))).toBe(true);
});
