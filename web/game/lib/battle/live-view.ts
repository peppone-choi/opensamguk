// 실시간 전투(P-C05) 보기 모델 — v2 SNAPSHOT(진행 중, 초안 protocol.ts) + AUTHORITY → 화면. 보드 V31K6v2BattleLive · LiveMany · MBattleLive ·
// MBattleLiveSheet(D24 승인).
// - 여럿 고르기(장수 머리 = 그 장수 부곡 전부, 일부만이면 −), 「내 부곡 전부」 · 「다 풀기」. 명령은 고른 부곡 모두에게 한 번에(C2 v2 #10 —
//   전부 받거나 전부 거절). 「내 부곡 전부」로 골랐으면 scope 는 allMine(서버가 내 실제 RETINUE 전체로 푼다), 하나씩 골랐으면 sourceKeys.
// - 남은 시간은 서버 규칙 핀(tickHz · maxTicks)이 있을 때만(C2 #15). 보드의 「3,000틱」은 예시다.
// - 조작자(사람 · AI)는 AUTHORITY 가 온 부곡만 표시한다(서버 producer 몫 — 연결 부재를 화면이 AI 로 추정하지 않는다).
import { toJoinView, type JoinUnitView } from './join-view';
import { sourceKeyId, type BattleOrder, type CommandScope, type Maybe, type RallyPoint, type Snapshot } from './protocol';

export interface LiveUnitView extends JoinUnitView {
    readonly order: BattleOrder | null;
    readonly rally: RallyPoint | null;
    /** AUTHORITY 가 온 부곡만. 없으면 null(표시 안 함). */
    readonly controller: 'HUMAN' | 'AI' | null;
}

export interface LiveGroupView {
    readonly generalId: number;
    readonly units: readonly LiveUnitView[];
    readonly troopsTotal: number;
}

export interface LiveView {
    readonly groups: readonly LiveGroupView[];
    readonly units: readonly LiveUnitView[];
    readonly boardId: number;
    readonly tick: number;
    /** 서버 규칙 핀이 있을 때만 남은 초. */
    readonly remainingSeconds: number | null;
    readonly maxTicks: Maybe<number>;
    readonly environment: Snapshot['environment'];
}

export function toLiveView(snapshot: Snapshot, controllers: ReadonlyMap<string, 'HUMAN' | 'AI'>): LiveView {
    const base = toJoinView(snapshot, 0);
    const extra = new Map(snapshot.units.map((u) => [sourceKeyId(u.sourceKey), u]));
    const units = base.units.map((u): LiveUnitView => ({ ...u, order: extra.get(u.id)?.order ?? null, rally: extra.get(u.id)?.rally ?? null, controller: controllers.get(u.id) ?? null }));
    const groups = base.groups.map((g) => ({ ...g, units: g.units.map((u) => units.find((x) => x.id === u.id)!) }));
    const hz = snapshot.tickHz.value;
    const max = snapshot.maxTicks.value;
    const remainingSeconds = hz != null && max != null ? Math.max(0, Math.ceil((max - snapshot.tick) / hz)) : null;
    return { groups, units, boardId: snapshot.field.boardId, tick: snapshot.tick, remainingSeconds, maxTicks: snapshot.maxTicks, environment: snapshot.environment };
}

/** 고른 상태 — allMine 은 「내 부곡 전부」로 골랐을 때만 참(하나라도 손으로 바꾸면 거짓). */
export interface Selection {
    readonly ids: ReadonlySet<string>;
    readonly allMine: boolean;
}

export const EMPTY_SELECTION: Selection = { ids: new Set(), allMine: false };

export function toggleUnit(sel: Selection, id: string): Selection {
    const ids = new Set(sel.ids);
    if (ids.has(id)) ids.delete(id);
    else ids.add(id);
    return { ids, allMine: false };
}

export type GroupState = 'all' | 'none' | 'mixed';

export function groupState(group: LiveGroupView, sel: Selection): GroupState {
    const n = group.units.filter((u) => sel.ids.has(u.id)).length;
    return n === 0 ? 'none' : n === group.units.length ? 'all' : 'mixed';
}

/** 장수 머리 — 전부 골라져 있으면 그 장수 부곡을 모두 풀고, 아니면 모두 고른다. */
export function toggleGroup(group: LiveGroupView, sel: Selection): Selection {
    const ids = new Set(sel.ids);
    const all = groupState(group, sel) === 'all';
    for (const u of group.units) {
        if (all) ids.delete(u.id);
        else ids.add(u.id);
    }
    return { ids, allMine: false };
}

/** 판에서 고르기(사각형) — 그 안 내 부곡만 고른다(앞 고르기를 바꾼다). 내 부곡이 아닌 id 는 버린다. */
export function selectOnly(view: LiveView, ids: readonly string[]): Selection {
    const mine = new Set(view.units.map((u) => u.id));
    return { ids: new Set(ids.filter((id) => mine.has(id))), allMine: false };
}

/** 판 묶음 깃발 글자 — 부곡 id → 장수 차례(1부터, 목록 「장수 n」과 같다). */
export function groupNumbers(view: LiveView): ReadonlyMap<string, number> {
    return new Map(view.groups.flatMap((g, gi) => g.units.map((u) => [u.id, gi + 1] as const)));
}

export function selectAllMine(view: LiveView): Selection {
    return { ids: new Set(view.units.map((u) => u.id)), allMine: true };
}

/** 명령 대상 — 고른 것이 없으면 null(명령 막대가 사유와 함께 막는다). */
export function commandScope(view: LiveView, sel: Selection): CommandScope | null {
    if (sel.ids.size === 0) return null;
    if (sel.allMine) return { allMine: true };
    return { sourceKeys: view.units.filter((u) => sel.ids.has(u.id)).map((u) => u.sourceKey) };
}

/** 고른 부곡들의 지금 집결점이 모두 같으면 그 값, 섞였거나 모르면 null. */
export function sharedRally(view: LiveView, sel: Selection): RallyPoint | null {
    const rallies = new Set(view.units.filter((u) => sel.ids.has(u.id)).map((u) => u.rally));
    if (rallies.size !== 1) return null;
    const [only] = [...rallies];
    return only ?? null;
}
