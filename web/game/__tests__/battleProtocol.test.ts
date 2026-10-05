// 실시간 전투 wire 어댑터(초안, C2 v2 계약) · 참가 · 배치 보기 모델 — 고정 자료는 이 시험 안에만 있다(제품 화면에 가짜 전투 없음).
// Long = 10진 문자열 · 봉투 검사 · SNAPSHOT/ACK 해석 · 모르는 프레임은 무시 · DEPLOYMENT_MOVE 모양 · 활성 목록 단계 ·
// 장수별 묶음 · 배치 구역 안 옮기기/맞바꾸기/막힘 · 남은 시간 보정 · 서버가 못 준 값은 null + 사유(지어내지 않음).
import { describe, expect, it } from 'vitest';
import { applyAcceptedMove, boardTap, formatClock, moveTarget, secondsLeft, toJoinView } from '../lib/battle/join-view';
import {
    battleSocketUrl, decodeServerFrame, deploymentMove, isInt, isLongString, isSourceKey, joinTicketPath, sourceKeyId, type Snapshot, type SourceKey,
} from '../lib/battle/protocol';

// ---- K6-11 활성 목록(GET /api/battles/active?generalId=) — v2 행 제안 해석 초안 ----
// 제품이 아직 쓰지 않아(P-C04 목록은 C2 대기) 시험 쪽에 둔다(K10 래칫 「시험 전용 export」, 10-05). 목록 화면을 만들 때 lib/battle 로 옮긴다.

/** 표시 단계(6203행 정정 표). 이 여덟 밖의 값은 화면이 「상태 확인 중」으로 둔다. */
const DISPLAY_PHASES = ['JOINING', 'LIVE', 'RESOLVING', 'RESULT_PENDING', 'RESULT_BLOCKED', 'ENDED', 'QUARANTINED'] as const;
type DisplayPhase = (typeof DISPLAY_PHASES)[number];

interface ActiveBattleRow {
    /** 10진 문자열 그대로(숫자로 바꾸지 않는다 — K6 소비 답). */
    readonly battleId: string;
    readonly worldId: number;
    /** 코드 목록은 C2 대기 — 문자열 그대로. */
    readonly kind: string;
    readonly sourcePhase: string;
    /** 아는 여덟 값이면 그 값, 아니면 null(「상태 확인 중」). */
    readonly phase: DisplayPhase | null;
    readonly pacingMode: string | null;
    /** JOINING 일 때만 의미가 있다. 없으면 남은 시간을 그리지 않는다. */
    readonly joinDeadlineAt: string | null;
    readonly mySourceKeys: readonly SourceKey[];
}

function decodeActiveBattles(raw: unknown): ActiveBattleRow[] | null {
    if (!Array.isArray(raw)) return null;
    const rows: ActiveBattleRow[] = [];
    for (const r of raw as Record<string, unknown>[]) {
        if (r == null || typeof r !== 'object') return null;
        const battleId = typeof r.battleId === 'string' ? r.battleId : typeof r.battleId === 'number' ? String(r.battleId) : null;
        if (!battleId || !isInt(r.worldId, 0)) return null;
        const sk = (r.mySeat as { sourceKeys?: unknown } | null | undefined)?.sourceKeys;
        const keys = Array.isArray(sk) ? sk.filter(isSourceKey) : [];
        const phase = typeof r.phase === 'string' && (DISPLAY_PHASES as readonly string[]).includes(r.phase) ? (r.phase as DisplayPhase) : null;
        rows.push({
            battleId,
            worldId: r.worldId as number,
            kind: typeof r.kind === 'string' ? r.kind : '',
            sourcePhase: typeof r.sourcePhase === 'string' ? r.sourcePhase : '',
            phase,
            pacingMode: typeof r.pacingMode === 'string' ? r.pacingMode : null,
            joinDeadlineAt: typeof r.joinDeadlineAt === 'string' ? r.joinDeadlineAt : null,
            mySourceKeys: keys,
        });
    }
    return rows;
}

const R = (sourceId: number) => ({ kind: 'RETINUE' as const, sourceId });

const snapshotFrame = (over: Record<string, unknown> = {}) => JSON.stringify({
    schemaVersion: 2, t: 'SNAPSHOT', battleId: '9001', sessionEpoch: '3', worldId: 7, tick: 0, eventSeq: '12', pacingMode: 'REALTIME',
    joinDeadlineAt: '2026-10-05T05:00:42+09:00', authorityRevision: '5',
    field: { boardId: 4, kind: 'FIELD', terrainInputSha256: 'a'.repeat(64) },
    units: [
        { sourceKey: R(11), ownerGeneralId: 7, cell: { row: 30, col: 10 }, troops: 800, morale: 100, order: null, rally: null },
        { sourceKey: R(12), ownerGeneralId: 7, cell: { row: 31, col: 10 }, troops: 600, morale: 90, order: 'DEFEND', rally: 'HOME' },
        { sourceKey: R(21), ownerGeneralId: 8, cell: { row: 32, col: 12 }, troops: 500, morale: 100, order: null, rally: null },
    ],
    deployment: {
        revision: '0', defaultPinned: true, remainingMillis: 42_000,
        allowedCells: [{ row: 30, col: 10 }, { row: 31, col: 10 }, { row: 32, col: 12 }, { row: 33, col: 12 }],
        ownPositions: [{ sourceKey: R(11), cell: { row: 30, col: 10 } }, { sourceKey: R(12), cell: { row: 31, col: 10 } }, { sourceKey: R(21), cell: { row: 32, col: 12 } }],
    },
    tickHz: null, maxTicks: null, unavailableReason: 'RULE_PIN_MISSING',
    environment: { weather: null, night: null, season: null, objective: null, unavailableReason: 'SOURCE_NOT_PINNED' },
    ...over,
});

function snapshot(over: Record<string, unknown> = {}): Snapshot {
    const r = decodeServerFrame(snapshotFrame(over));
    if (!r.ok || r.frame.t !== 'SNAPSHOT') throw new Error(`decode ${JSON.stringify(r)}`);
    return r.frame;
}

describe('wire 기초', () => {
    it('Long 은 10진 문자열만 — 선행 0 · 부호 · 숫자형 · 2^63 넘침 거절, epoch 는 1부터', () => {
        expect(isLongString('0')).toBe(true);
        expect(isLongString('9223372036854775807')).toBe(true);
        expect(isLongString('9223372036854775808')).toBe(false);
        expect(isLongString('012')).toBe(false);
        expect(isLongString('-1')).toBe(false);
        expect(isLongString(5)).toBe(false);
        expect(isLongString('0', true)).toBe(false);
    });

    it('부곡 열쇠 문자열 · 접속 경로(티켓 · WS) — 티켓은 하위 프로토콜로', () => {
        expect(sourceKeyId(R(11))).toBe('RETINUE:11');
        expect(sourceKeyId({ kind: 'CITY_GARRISON_BUGOK', cityId: 3, sourceId: 4 })).toBe('CITY_GARRISON_BUGOK:3:4');
        expect(joinTicketPath(7, '9001')).toBe('/api/battles/7/9001/join-ticket');
        expect(battleSocketUrl('https://sam.example', 'pep', 7, '9001', '12')).toBe('wss://sam.example/ws/battles/pep/7/9001?lastSeenEventSeq=12');
    });

    it('봉투가 틀리면 오류(v1 프레임 · epoch 숫자형 · JSON 아님), 모르는 t 는 무시', () => {
        expect(decodeServerFrame('nope')).toEqual({ ok: false, error: 'JSON' });
        expect(decodeServerFrame(snapshotFrame({ schemaVersion: 1 }))).toEqual({ ok: false, error: 'ENVELOPE' });
        expect(decodeServerFrame(snapshotFrame({ sessionEpoch: 3 }))).toEqual({ ok: false, error: 'ENVELOPE' });
        expect(decodeServerFrame(JSON.stringify({ schemaVersion: 2, t: 'DELTA', battleId: '9001', sessionEpoch: '3' })))
            .toEqual({ ok: true, frame: { t: 'IGNORED', type: 'DELTA' } });
    });

    it('SNAPSHOT — 칸 범위 밖 · 판 번호 밖은 오류, 시간 · 환경은 서버 값이 없으면 null + 사유(v1 숫자를 채우지 않음)', () => {
        expect(decodeServerFrame(snapshotFrame({ field: { boardId: 214, kind: 'FIELD' } }))).toEqual({ ok: false, error: 'SNAPSHOT_FIELD' });
        expect(decodeServerFrame(snapshotFrame({ units: [{ sourceKey: R(1), ownerGeneralId: 7, cell: { row: 64, col: 0 }, troops: 1, morale: 1 }] })))
            .toEqual({ ok: false, error: 'SNAPSHOT_UNIT' });
        const s = snapshot();
        expect(s.tickHz).toEqual({ value: null, reason: 'RULE_PIN_MISSING' });
        expect(s.environment.weather).toEqual({ value: null, reason: 'SOURCE_NOT_PINNED' });
        expect(s.units[1]).toMatchObject({ order: 'DEFEND', rally: 'HOME' });
    });

    it('ACK — 영수증 판정 · 표 안 사유만 코드, 표 밖 사유는 null(「알 수 없는 거절」)', () => {
        const ack = (receipt: Record<string, unknown>) => decodeServerFrame(JSON.stringify({
            schemaVersion: 2, t: 'ACK', battleId: '9001', sessionEpoch: '3', clientCommandId: 'c1', inputId: 'battle.deployment_move', receipt, replayed: false,
        }));
        expect(ack({ verdict: 'ACCEPTED', deploymentRevisionBefore: '0', deploymentRevisionAfter: '1' }))
            .toMatchObject({ ok: true, frame: { t: 'ACK', verdict: 'ACCEPTED', reasonCode: null, deploymentRevisionAfter: '1' } });
        expect(ack({ verdict: 'REJECTED', reasonCode: 'STALE_DEPLOYMENT' })).toMatchObject({ ok: true, frame: { verdict: 'REJECTED', reasonCode: 'STALE_DEPLOYMENT' } });
        expect(ack({ verdict: 'REJECTED', reasonCode: 'WHATEVER' })).toMatchObject({ ok: true, frame: { verdict: 'REJECTED', reasonCode: null } });
    });

    it('DEPLOYMENT_MOVE — 한 부곡 · 한 칸 · 기대 값 셋(10진 문자열)', () => {
        expect(JSON.parse(deploymentMove({
            clientCommandId: 'c1', sourceKey: R(11), targetCell: { row: 33, col: 12 }, expectedEpoch: '3', expectedAuthorityRevision: '5', expectedDeploymentRevision: '0',
        }))).toEqual({
            schemaVersion: 2, t: 'DEPLOYMENT_MOVE', clientCommandId: 'c1', sourceKey: { kind: 'RETINUE', sourceId: 11 }, targetCell: { row: 33, col: 12 },
            expectedEpoch: '3', expectedAuthorityRevision: '5', expectedDeploymentRevision: '0',
        });
    });

    it('활성 목록 — 아는 여덟 단계만 표시 단계, 그 밖은 null · battleId 는 문자열 그대로 · 배열 아님은 null', () => {
        expect(decodeActiveBattles({})).toBeNull();
        const rows = decodeActiveBattles([
            { battleId: '9001', worldId: 7, kind: 'FIELD', sourcePhase: 'READY', phase: 'JOINING', pacingMode: 'REALTIME', joinDeadlineAt: '2026-10-05T05:00:42+09:00', mySeat: { sourceKeys: [R(11)] } },
            { battleId: '9002', worldId: 7, kind: 'X', sourcePhase: 'NEW_ONE', phase: 'NEW_ONE', mySeat: { sourceKeys: [] } },
        ])!;
        expect(rows.map((r) => [r.battleId, r.phase, r.mySourceKeys.length])).toEqual([['9001', 'JOINING', 1], ['9002', null, 0]]);
    });
});

describe('참가 · 배치 보기', () => {
    it('장수별로 묶고 차례 번호를 매긴다 — 병력 합, 배치 위치는 ownPositions', () => {
        const v = toJoinView(snapshot(), 1_000);
        expect(v.groups.map((g) => [g.generalId, g.units.map((u) => u.index), g.troopsTotal])).toEqual([[7, [1, 2], 1400], [8, [1], 500]]);
        expect(v.revision).toBe('0');
        expect(v.defaultPinned).toBe(true);
        expect(v.boardId).toBe(4);
    });

    it('배치 단계의 부곡은 ownPositions 에 있는 것만 — units[] 에 섞여 온 남의 부곡은 그리지 않는다, 배치 단계가 아니면 거르지 않는다', () => {
        const extra = { sourceKey: R(31), ownerGeneralId: 9, cell: { row: 33, col: 12 }, troops: 900, morale: 100, order: null, rally: null };
        const units = [...(JSON.parse(snapshotFrame()).units as unknown[]), extra];
        const v = toJoinView(snapshot({ units }), 0);
        expect(v.units.map((u) => u.id)).toEqual(['RETINUE:11', 'RETINUE:12', 'RETINUE:21']);
        expect(v.groups.map((g) => g.generalId)).toEqual([7, 8]);
        expect(toJoinView(snapshot({ units, deployment: null }), 0).units.map((u) => u.id)).toContain('RETINUE:31');
    });

    it('남은 시간 — 서버 남은 밀리초를 받은 시각으로 보정, 없으면 joinDeadlineAt(기기 시계 · 약)', () => {
        const v = toJoinView(snapshot(), 1_000);
        expect(v.deadline).toEqual({ at: 43_000, approx: false });
        expect(secondsLeft(v.deadline, 1_000)).toBe(42);
        expect(formatClock(42)).toBe('0:42');
        expect(secondsLeft(v.deadline, 99_000)).toBe(0);
        const noRemain = snapshot({ deployment: { ...JSON.parse(snapshotFrame()).deployment, remainingMillis: undefined } });
        expect(toJoinView(noRemain, 1_000).deadline).toEqual({ at: Date.parse('2026-10-05T05:00:42+09:00'), approx: true });
    });

    it('옮기기 — 구역 안 빈 칸은 옮김, 내 부곡 칸은 맞바꿈, 구역 밖 · 같은 칸 · 배치 단계 아님은 막힘(사유)', () => {
        const v = toJoinView(snapshot(), 0);
        expect(moveTarget(v, 'RETINUE:11', { row: 33, col: 12 })).toEqual({ kind: 'move' });
        expect(moveTarget(v, 'RETINUE:11', { row: 31, col: 10 })).toEqual({ kind: 'swap', withId: 'RETINUE:12' });
        expect(moveTarget(v, 'RETINUE:11', { row: 5, col: 5 })).toMatchObject({ kind: 'blocked', reason: expect.stringMatching(/배치 구역 밖/) });
        expect(moveTarget(v, 'RETINUE:11', { row: 30, col: 10 })).toMatchObject({ kind: 'blocked' });
        expect(moveTarget(toJoinView(snapshot({ deployment: null }), 0), 'RETINUE:11', { row: 33, col: 12 })).toMatchObject({ kind: 'blocked', reason: '배치 시간이 아닙니다' });
    });

    it('판 누르기 — 고른 부곡이 있으면 내 부곡 칸도 칸 누름(→ 맞바꾸기), 고른 부곡이 없을 때만 그 부곡 고르기', () => {
        const v = toJoinView(snapshot(), 0);
        // 보드 V31K6v2BattleJoin 「내 부곡이 있는 칸이면 둘을 맞바꾼다」 — 판에서 맞바꾸기에 닿아야 한다(#1333 리뷰).
        expect(boardTap(v.units, 'RETINUE:11', { row: 31, col: 10 })).toEqual({ kind: 'cell', cell: { row: 31, col: 10 } });
        expect(moveTarget(v, 'RETINUE:11', { row: 31, col: 10 })).toEqual({ kind: 'swap', withId: 'RETINUE:12' });
        expect(boardTap(v.units, 'RETINUE:11', { row: 33, col: 12 })).toEqual({ kind: 'cell', cell: { row: 33, col: 12 } });
        expect(boardTap(v.units, 'RETINUE:11', { row: 30, col: 10 })).toEqual({ kind: 'cell', cell: { row: 30, col: 10 } });
        expect(boardTap(v.units, null, { row: 31, col: 10 })).toEqual({ kind: 'pickUnit', id: 'RETINUE:12' });
    });

    it('받아들인 옮기기만 적용 — 맞바꾸면 두 부곡 자리가 바뀌고 revision 은 영수증 값, 기본 배치 표시는 꺼진다', () => {
        const v = applyAcceptedMove(toJoinView(snapshot(), 0), 'RETINUE:11', { row: 31, col: 10 }, '1');
        expect(v.units.find((u) => u.id === 'RETINUE:11')!.cell).toEqual({ row: 31, col: 10 });
        expect(v.units.find((u) => u.id === 'RETINUE:12')!.cell).toEqual({ row: 30, col: 10 });
        expect(v.groups[0].units.find((u) => u.id === 'RETINUE:11')!.cell).toEqual({ row: 31, col: 10 });
        expect(v.revision).toBe('1');
        expect(v.defaultPinned).toBe(false);
    });
});
