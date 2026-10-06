// 실시간 전투 보기 모델 · COMMAND 형 · AUTHORITY 해석(C2 v2 초안) — 고정 자료는 이 시험 안에만.
// 여럿 고르기(하나 · 장수 머리 · 내 부곡 전부 · 다 풀기) · 명령 대상(sourceKeys / allMine) · 집결점이 섞이면 null(지어내지 않음) ·
// 남은 시간은 서버 규칙 핀이 있을 때만 · AI 표지는 AUTHORITY 가 온 부곡만.
import { describe, expect, it } from 'vitest';
import {
    commandScope, EMPTY_SELECTION, groupNumbers, groupState, selectAllMine, selectOnly, sharedRally, toggleGroup, toggleUnit, toLiveView,
} from '../lib/battle/live-view';
import { battleCommand, decodeServerFrame, type Snapshot } from '../lib/battle/protocol';

const R = (sourceId: number) => ({ kind: 'RETINUE' as const, sourceId });

function snapshot(over: Record<string, unknown> = {}): Snapshot {
    const r = decodeServerFrame(JSON.stringify({
        schemaVersion: 2, t: 'SNAPSHOT', battleId: '9001', sessionEpoch: '3', worldId: 7, tick: 120, eventSeq: '40', pacingMode: 'REALTIME', joinDeadlineAt: null,
        authorityRevision: '6', field: { boardId: 4, kind: 'FIELD' },
        units: [
            { sourceKey: R(11), ownerGeneralId: 7, cell: { row: 30, col: 14 }, troops: 780, morale: 96, order: 'CHARGE', rally: 'HOME' },
            { sourceKey: R(12), ownerGeneralId: 7, cell: { row: 31, col: 14 }, troops: 590, morale: 88, order: 'CHARGE', rally: 'HOME' },
            { sourceKey: R(21), ownerGeneralId: 8, cell: { row: 32, col: 15 }, troops: 500, morale: 100, order: 'DEFEND', rally: 'CENTER' },
        ],
        deployment: null,
        environment: { weather: null, night: null, season: null, objective: null, unavailableReason: 'SOURCE_NOT_PINNED' },
        ...over,
    }));
    if (!r.ok || r.frame.t !== 'SNAPSHOT') throw new Error('decode');
    return r.frame;
}

describe('실시간 전투 보기', () => {
    it('장수별 묶음 · 지금 명령 · 집결점, 남은 시간은 규칙 핀이 있을 때만', () => {
        const v = toLiveView(snapshot(), new Map());
        expect(v.groups.map((g) => g.units.map((u) => [u.id, u.order, u.rally]))).toEqual([
            [['RETINUE:11', 'CHARGE', 'HOME'], ['RETINUE:12', 'CHARGE', 'HOME']], [['RETINUE:21', 'DEFEND', 'CENTER']],
        ]);
        expect(v.remainingSeconds).toBeNull();
        expect(toLiveView(snapshot({ tickHz: 10, maxTicks: 3000 }), new Map()).remainingSeconds).toBe(288);
    });

    it('AI 표지는 AUTHORITY 가 온 부곡만(나머지는 null — 연결 부재로 추정하지 않음)', () => {
        const v = toLiveView(snapshot(), new Map([['RETINUE:12', 'AI' as const]]));
        expect(v.units.map((u) => u.controller)).toEqual([null, 'AI', null]);
    });

    it('고르기 — 하나 · 장수 머리(전부 ↔ 풀기, 일부면 mixed) · 내 부곡 전부(allMine) · 다 풀기', () => {
        const v = toLiveView(snapshot(), new Map());
        let sel = toggleUnit(EMPTY_SELECTION, 'RETINUE:11');
        expect(groupState(v.groups[0], sel)).toBe('mixed');
        sel = toggleGroup(v.groups[0], sel);
        expect(groupState(v.groups[0], sel)).toBe('all');
        expect(toggleGroup(v.groups[0], sel).ids.size).toBe(0);
        const all = selectAllMine(v);
        expect(all).toMatchObject({ allMine: true });
        expect(all.ids.size).toBe(3);
        expect(toggleUnit(all, 'RETINUE:21').allMine).toBe(false);
    });

    it('명령 대상 — 없으면 null, 하나씩 고르면 sourceKeys, 내 부곡 전부면 allMine', () => {
        const v = toLiveView(snapshot(), new Map());
        expect(commandScope(v, EMPTY_SELECTION)).toBeNull();
        expect(commandScope(v, toggleUnit(EMPTY_SELECTION, 'RETINUE:12'))).toEqual({ sourceKeys: [R(12)] });
        expect(commandScope(v, selectAllMine(v))).toEqual({ allMine: true });
    });

    it('판에서 고르기 — 사각형 안 내 부곡만(앞 고르기를 바꿈 · allMine 아님 · 남의 id 버림), 묶음 깃발 글자는 장수 차례', () => {
        const v = toLiveView(snapshot(), new Map());
        const sel = selectOnly(v, ['RETINUE:12', 'RETINUE:21', 'RETINUE:99']);
        expect([...sel.ids]).toEqual(['RETINUE:12', 'RETINUE:21']);
        expect(sel.allMine).toBe(false);
        expect(selectOnly(v, []).ids.size).toBe(0);
        expect([...groupNumbers(v)]).toEqual([['RETINUE:11', 1], ['RETINUE:12', 1], ['RETINUE:21', 2]]);
    });

    it('집결점 — 고른 부곡이 모두 같으면 그 값, 섞이면 null', () => {
        const v = toLiveView(snapshot(), new Map());
        expect(sharedRally(v, toggleGroup(v.groups[0], EMPTY_SELECTION))).toBe('HOME');
        expect(sharedRally(v, selectAllMine(v))).toBeNull();
    });
});

describe('wire — COMMAND · AUTHORITY', () => {
    it('COMMAND — sourceKeys 는 중복을 빼고 정렬, allMine 은 그대로, 명령 · 집결점 · 기대 값 · 틱', () => {
        const base = { clientCommandId: 'c1', expectedEpoch: '3', expectedAuthorityRevision: '6', issuedTick: 120, order: 'CHARGE' as const, rally: 'HOME' as const };
        expect(JSON.parse(battleCommand({ ...base, scope: { sourceKeys: [R(12), R(11), R(12)] } }))).toEqual({
            schemaVersion: 2, t: 'COMMAND', clientCommandId: 'c1', expectedEpoch: '3', expectedAuthorityRevision: '6', issuedTick: 120,
            scope: { sourceKeys: [R(11), R(12)] }, intentType: 'CHARGE', intentPayload: { rally: 'HOME' },
        });
        expect(JSON.parse(battleCommand({ ...base, scope: { allMine: true } })).scope).toEqual({ allMine: true });
    });

    it('AUTHORITY — 사람 · AI 만, 지휘권 revision 은 10진 문자열', () => {
        const frame = (over: Record<string, unknown>) => decodeServerFrame(JSON.stringify({
            schemaVersion: 2, t: 'AUTHORITY', battleId: '9001', sessionEpoch: '3', sourceKey: R(12), controller: 'AI', reason: 'DEADLINE_PASSED', authorityRevision: '7', ...over,
        }));
        expect(frame({})).toEqual({ ok: true, frame: { t: 'AUTHORITY', sourceKey: R(12), controller: 'AI', reason: 'DEADLINE_PASSED', authorityRevision: '7' } });
        expect(frame({ controller: 'BOT' })).toEqual({ ok: false, error: 'AUTHORITY' });
        expect(frame({ authorityRevision: 7 })).toEqual({ ok: false, error: 'AUTHORITY' });
    });
});
