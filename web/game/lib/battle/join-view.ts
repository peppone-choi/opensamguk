// 전투 참가 · 배치(P-C03) 보기 모델 — v2 SNAPSHOT(초안, protocol.ts) → 화면. 보드 V31K6v2BattleJoin · MBattleJoin(D24 승인 · D29 배율).
//
// - 내 군단 부곡 전부가 나간다(D-BATTLE 2C). 장수별로 묶는다(1A — 자기 군단 부곡만 지휘). 묶음 머리의 장수 이름 · 초상, 부곡 이름 · 병종은
//   서버가 아직 주지 않는다(C2 v2 답 #2 — ownerGeneralId 만). 그래서 이름 대신 「장수별 차례 번호」로만 가르고, 지어낸 이름을 쓰지 않는다.
// - 배치 구역(allowedCells) 안 칸을 누르면 그리로 옮기고, 내 부곡이 있는 칸이면 맞바꾼다. 구역 밖은 사유와 함께 막는다.
// - 남은 시간: 서버의 remainingMillis 를 받은 시각으로 보정한다. 없으면 joinDeadlineAt 을 기기 시계로 세고 「약」을 붙인다(K6 소비 답).
// - 사기 경고(「물러나며 명령을 받지 않는다」)는 엔진 규칙 값(moraleRetreatBelow)이 규칙 핀에 있고 SNAPSHOT 에 실려 오지 않아 그리지 않는다.
import { sameCell, sourceKeyId, type Cell, type Maybe, type Snapshot, type SourceKey } from './protocol';

export interface JoinUnitView {
    readonly id: string;
    readonly sourceKey: SourceKey;
    readonly ownerGeneralId: number;
    /** 같은 장수 안의 차례(1부터) — 화면 이름표 「부곡 n」. 서버 부곡 이름이 오면 그것으로 바꾼다. */
    readonly index: number;
    readonly cell: Cell;
    readonly troops: number;
    readonly morale: number;
}

export interface JoinGroupView {
    readonly generalId: number;
    readonly units: readonly JoinUnitView[];
    readonly troopsTotal: number;
}

export interface JoinView {
    readonly groups: readonly JoinGroupView[];
    readonly units: readonly JoinUnitView[];
    readonly allowedCells: readonly Cell[];
    /** 배치 단계가 아니면(deployment 없음) null — 옮기기를 그리지 않는다. */
    readonly revision: string | null;
    readonly defaultPinned: boolean;
    /** 개전 시각(epoch ms). approx = 기기 시계로 센 값. */
    readonly deadline: { readonly at: number; readonly approx: boolean } | null;
    readonly boardId: number;
    readonly tickHz: Maybe<number>;
    readonly maxTicks: Maybe<number>;
    readonly environment: Snapshot['environment'];
}

export const cellKey = (c: Cell): string => `${c.row}:${c.col}`;

export function toJoinView(snapshot: Snapshot, receivedAt: number): JoinView {
    const d = snapshot.deployment;
    // 배치 단계에서는 ownPositions 가 정본 위치다(units[].cell 은 그 전 상태일 수 있다).
    const posOf = new Map(d?.ownPositions.map((p) => [sourceKeyId(p.sourceKey), p.cell]) ?? []);
    const counter = new Map<number, number>();
    const units = snapshot.units.map((u): JoinUnitView => {
        const n = (counter.get(u.ownerGeneralId) ?? 0) + 1;
        counter.set(u.ownerGeneralId, n);
        const id = sourceKeyId(u.sourceKey);
        return { id, sourceKey: u.sourceKey, ownerGeneralId: u.ownerGeneralId, index: n, cell: posOf.get(id) ?? u.cell, troops: u.troops, morale: u.morale };
    });
    const groups: JoinGroupView[] = [];
    for (const u of units) {
        let g = groups.find((x) => x.generalId === u.ownerGeneralId) as { generalId: number; units: JoinUnitView[]; troopsTotal: number } | undefined;
        if (!g) {
            g = { generalId: u.ownerGeneralId, units: [], troopsTotal: 0 };
            groups.push(g);
        }
        g.units.push(u);
        g.troopsTotal += u.troops;
    }
    let deadline: JoinView['deadline'] = null;
    if (d?.remainingMillis != null) deadline = { at: receivedAt + d.remainingMillis, approx: false };
    else if (snapshot.joinDeadlineAt) {
        const at = Date.parse(snapshot.joinDeadlineAt);
        if (Number.isFinite(at)) deadline = { at, approx: true };
    }
    return {
        groups,
        units,
        allowedCells: d?.allowedCells ?? [],
        revision: d?.revision ?? null,
        defaultPinned: d?.defaultPinned ?? false,
        deadline,
        boardId: snapshot.field.boardId,
        tickHz: snapshot.tickHz,
        maxTicks: snapshot.maxTicks,
        environment: snapshot.environment,
    };
}

export type MoveTarget =
    | { readonly kind: 'move' }
    | { readonly kind: 'swap'; readonly withId: string }
    | { readonly kind: 'blocked'; readonly reason: string };

/** 고른 부곡을 그 칸으로 옮길 수 있는지 — 서버 판정 전의 화면 안내다(최종 판정은 ACK). */
export function moveTarget(view: JoinView, unitId: string, cell: Cell): MoveTarget {
    if (view.revision == null) return { kind: 'blocked', reason: '배치 시간이 아닙니다' };
    if (!view.allowedCells.some((c) => sameCell(c, cell))) return { kind: 'blocked', reason: '배치 구역 밖입니다 — 초록 점선 안 칸만 옮길 수 있습니다' };
    const there = view.units.find((u) => u.id !== unitId && sameCell(u.cell, cell));
    if (there) return { kind: 'swap', withId: there.id };
    const self = view.units.find((u) => u.id === unitId);
    if (self && sameCell(self.cell, cell)) return { kind: 'blocked', reason: '이미 그 칸에 있습니다' };
    return { kind: 'move' };
}

/** 서버가 받아들인 옮기기(ACK ACCEPTED)를 보기에 적용한다 — 옮기기 · 맞바꾸기, 배치 revision 을 영수증 값으로. */
export function applyAcceptedMove(view: JoinView, unitId: string, cell: Cell, revisionAfter: string | null): JoinView {
    const target = moveTarget(view, unitId, cell);
    if (target.kind === 'blocked') return view;
    const from = view.units.find((u) => u.id === unitId)?.cell;
    if (!from) return view;
    const units = view.units.map((u) => {
        if (u.id === unitId) return { ...u, cell };
        if (target.kind === 'swap' && u.id === target.withId) return { ...u, cell: from };
        return u;
    });
    const groups = view.groups.map((g) => ({ ...g, units: g.units.map((u) => units.find((x) => x.id === u.id) ?? u) }));
    return { ...view, units, groups, defaultPinned: false, revision: revisionAfter ?? view.revision };
}

/** 남은 초(0 아래로 내려가지 않음). */
export function secondsLeft(deadline: JoinView['deadline'], now: number): number | null {
    if (!deadline) return null;
    return Math.max(0, Math.ceil((deadline.at - now) / 1000));
}

/** 「0:42」 꼴. */
export function formatClock(seconds: number): string {
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
