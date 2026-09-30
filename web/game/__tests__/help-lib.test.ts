import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { HELP_INDEX } from '../lib/help-index';
import { __resetHelpCache, helpApi, helpErrorKind, searchQuery } from '../lib/help';
import { costValue, helpText, inputName, timingLabel, whoLabel } from '../lib/help-labels';
import { formatHelpView, parseHelpView, type HelpView } from '../lib/help-route';
import { generalActionGroups, screenGroups, screenInputIds, type HelpScreen } from '../lib/help-screens';
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

test('approved new names replace the old command names', () => {
    expect(inputName('action.convertProficiency')).toBe('병종 바꿔 익히기');
    expect(inputName('action.tradeGrain')).toBe('쌀 사고팔기');
    expect(helpText('군량 습격으로 군량매매와 숙련전환을 막는다 · 군량')).toBe('보급 습격으로 쌀 사고팔기와 병종 바꿔 익히기를 막는다 · 쌀');
    expect(helpText('출사 결과가 확정되면')).toBe('출사 결과가 확정되면');
    expect(helpText('숙련전환이 끝나면 군량으로 군량매매를 한다')).toBe('병종 바꿔 익히기가 끝나면 쌀로 쌀 사고팔기를 한다');
    expect(helpText('군량과 금')).toBe('쌀과 금');
    expect(helpText('군량 습격은')).toBe('보급 습격은');
    expect(helpText('인접한 郡國의 제한된 정보')).toBe('인접한 군국의 제한된 정보');
    expect(helpText('다른 省으로')).toBe('다른 구역으로');
    expect(helpText('縣이')).toBe('현이');
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
