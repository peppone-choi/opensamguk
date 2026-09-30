// 영지 — 배치 · 방침 · 공사(P-T01)의 보기 모델. `/api/posts` · `/api/policies` · `/api/works` 를 화면 줄로 바꾼다. React 없음.
//
// 가능 여부는 서버 값(available · placeable · settable · blocked)만 쓴다. 불가 항목은 숨기지 않고 사유와 함께 보인다
// (옛 DomesticPanels 는 available=false 자리를 사유 없이 뺐다 — 설계서 P-T01).

import type { TargetCandidate } from '@opensamguk/ui';
import { CAMPAIGN_RESOURCE_LABELS, type Blocked, type CodeLabel, type CountyWorks, type GamePhase, type PlacementCard, type Policies, type PostOption, type Posts, type Stock, type Works } from './campaign-reads';
import { TURN_PHASE_LABELS } from './format';

/** 서버가 사유 없이 불가를 줬을 때 — 사유를 짓지 않는다(K3 InputAction MISSING_REASON 과 같은 글자). */
export const MISSING_REASON = '사유를 받지 못했습니다';

// ── 배치 ─────────────────────────────────────────────────────────────

/** 자리 종류 이름. 「해제」와 「자리에서 풀기」가 둘 있던 것을 하나로(설계서 P-T01). */
export function postKindLabel(p: Pick<PostOption, 'post' | 'label'>): string {
    return p.post === 'NONE' ? '자리에서 풀기' : p.label;
}

function placeLabel(label: string, target: { label?: string | null } | null | undefined): string {
    const where = target?.label?.trim();
    return where ? `${label} · ${where}` : label;
}

export interface PlacementRow {
    readonly cardId: number;
    readonly generalId: number | null;
    readonly name: string;
    /** 지금 자리(「현령 · 장사현」). null = 미배치. */
    readonly now: string | null;
    /** 부임 행군 중(서버 state MOVING). */
    readonly moving: boolean;
    /** 다음 턴부터 바뀔 자리. */
    readonly pending: string | null;
    readonly placeable: boolean;
    readonly blocked: Blocked | null;
}

export function placementRows(posts: Posts): PlacementRow[] {
    return posts.cards.map((c) => ({
        cardId: c.cardId,
        generalId: c.generalId,
        name: c.name,
        now: c.active ? placeLabel(c.active.postLabel, c.active.target) : null,
        moving: c.active?.state === 'MOVING',
        pending: c.pending ? placeLabel(postKindLabel({ post: c.pending.post, label: c.pending.postLabel }), c.pending.target) : null,
        placeable: c.placeable,
        blocked: c.blocked,
    }));
}

export type TargetNeed = 'county' | 'nation' | 'here' | null;

export interface PostKindChoice {
    readonly post: string;
    readonly label: string;
    readonly available: boolean;
    /** 불가면 서버 사유(없으면 MISSING_REASON). */
    readonly reason: string | null;
    readonly code: string | null;
    /** 다음 단계 — 현을 고른다(현령) · 세력을 고른다(사자) · 지금 선 구역(정찰) · 없음(군단장 · 풀기). */
    readonly need: TargetNeed;
}

const NEED: Readonly<Record<string, TargetNeed>> = { MAGISTRATE: 'county', ENVOY: 'nation', SCOUT: 'here' };

/** 자리 종류 — 불가도 사유와 함께 모두(서버 순서 그대로). */
export function postKindChoices(posts: Posts): PostKindChoice[] {
    return posts.posts.map((p) => ({
        post: p.post,
        label: postKindLabel(p),
        available: p.available,
        reason: p.available ? null : p.blocked?.reason?.trim() || MISSING_REASON,
        code: p.available ? null : p.blocked?.code ?? null,
        need: NEED[p.post] ?? null,
    }));
}

/** 이미 다른 인물이 맡은 현 · 세력 — 서버가 occupied 로만 알려 준다. */
export const OCCUPIED_REASON = '다른 인물이 맡고 있습니다';

/**
 * 대상 후보(K3 TargetCandidate) — 현령은 현(지도 대상 고르기 place), 사자는 세력(목록만, 지도 칸 없음).
 * 맡은 사람이 있는 대상도 사유와 함께 보인다. 지도 칸(cell)은 화면이 城 표로 붙인다.
 */
export function targetCandidates(option: PostOption): TargetCandidate[] {
    return (option.targets ?? []).flatMap((t): TargetCandidate[] => {
        const id = option.post === 'ENVOY' ? t.nationId : t.countyId;
        if (id == null) return [];
        return [{
            targetKind: 'place',
            targetId: String(id),
            cityId: option.post === 'ENVOY' ? undefined : String(id),
            name: t.name,
            sub: t.commanderyName ?? undefined,
            available: !t.occupied,
            reason: t.occupied ? OCCUPIED_REASON : undefined,
        }];
    });
}

export type PlacementBody = Readonly<Record<string, unknown>>;

/**
 * 배치 입력 몸통(`POST /api/commands/placement/assign`) — 옛 DomesticPanels 와 같은 모양.
 * 정찰은 카드가 지금 선 구역에서 한다 — 카드 위치를 모르면 보내지 않고 사유를 돌려준다.
 */
export function placementBody(card: Pick<PlacementCard, 'cardId' | 'provinceId'>, post: string, targetId: string | null):
    { readonly body: PlacementBody } | { readonly error: string } {
    const body: Record<string, unknown> = { cardId: card.cardId, post };
    const need = NEED[post] ?? null;
    if (need === 'county' || need === 'nation') {
        if (!targetId) return { error: need === 'county' ? '맡길 현을 고르세요.' : '보낼 세력을 고르세요.' };
        body[need === 'county' ? 'countyId' : 'nationId'] = Number(targetId);
    }
    if (need === 'here') {
        if (!card.provinceId) return { error: '카드의 지금 위치를 알 수 없어 정찰을 보낼 수 없습니다.' };
        body.provinceId = card.provinceId;
    }
    return { body };
}

// ── 방침 ─────────────────────────────────────────────────────────────

/** 서버 Phase → 「200년 3월 중순」. 순이 1–3 밖이면 순을 뺀다. */
export function phaseText(p: GamePhase): string {
    const phase = TURN_PHASE_LABELS[p.phase - 1];
    return phase ? `${p.year}년 ${p.month}월 ${phase}` : `${p.year}년 ${p.month}월`;
}

/** 지금 방침이 어디서 왔는가(`PolicySource`). 모르는 값이면 칩을 그리지 않는다. */
export const POLICY_SOURCE_LABEL: Readonly<Record<string, string>> = { COMMANDERY: '군 방침', COUNTY: '현 방침', DEFAULT: '기본' };

/** 거두기(`policy.set` policy "NONE", 서버 `DomesticInputs.NONE`). */
export const POLICY_WITHDRAW = 'NONE';

export type PolicyScope = 'COUNTY' | 'COMMANDERY' | 'CORPS';

export interface PolicyRow {
    readonly scope: PolicyScope;
    /** 현 id · 군 id · 군단 명령 id — 입력 몸통의 대상. */
    readonly targetId: string;
    readonly name: string;
    /** 둘째 줄(현이면 군 이름, 군이면 현 수, 군단이면 지휘관). */
    readonly sub: string | null;
    /** 현령 — 「빈자리」 · 「이름」 · 「이름(부임 중)」. 현 줄만. */
    readonly seat: string | null;
    /** 지금 효력 있는 방침 이름. 없으면 null(「없음」). */
    readonly now: string | null;
    readonly source: string | null;
    readonly since: string | null;
    /** 다음 턴부터 바뀔 방침(거두기면 「거두기」). */
    readonly pending: string | null;
    readonly lastApplied: string | null;
    /** 지금 건 방침이 있는가 — 「방침 거두기」 선택지를 보일지. */
    readonly hasActive: boolean;
    readonly settable: boolean;
    readonly blocked: Blocked | null;
}

const pendingText = (p: { policy: string | null; label: string | null } | null) =>
    p ? (p.policy == null ? '거두기' : p.label ?? p.policy) : null;

export function countyPolicyRows(p: Policies): PolicyRow[] {
    return p.counties.map((c) => ({
        scope: 'COUNTY',
        targetId: String(c.countyId),
        name: c.name,
        sub: c.commanderyName,
        seat: c.seat ? (c.seat.placed ? c.seat.name : `${c.seat.name}(부임 중)`) : '빈자리',
        now: c.effective?.label ?? c.active?.label ?? null,
        source: c.effective ? POLICY_SOURCE_LABEL[c.effective.source] ?? null : null,
        since: c.active?.since ? phaseText(c.active.since) : null,
        pending: pendingText(c.pending),
        lastApplied: c.lastApplied ? `${phaseText(c.lastApplied.at)} · ${c.lastApplied.label}` : null,
        hasActive: c.active != null,
        settable: c.settable,
        blocked: c.blocked,
    }));
}

export function commanderyPolicyRows(p: Policies): PolicyRow[] {
    return (p.commanderies ?? []).map((c) => ({
        scope: 'COMMANDERY',
        targetId: c.commanderyId,
        name: c.name ?? '이름 없는 군',
        sub: `현 ${c.countyIds.length}곳`,
        seat: null,
        now: c.active?.label ?? null,
        source: null,
        since: c.active?.since ? phaseText(c.active.since) : null,
        pending: pendingText(c.pending),
        lastApplied: null,
        hasActive: c.active != null,
        settable: c.settable,
        blocked: c.blocked,
    }));
}

export function corpsPolicyRows(p: Policies): PolicyRow[] {
    return p.corps.map((c) => ({
        scope: 'CORPS',
        targetId: c.orderId,
        name: c.commanderName ? `${c.commanderName} 군단` : '군단',
        sub: null,
        seat: null,
        now: c.active?.label ?? null,
        source: null,
        since: null,
        pending: pendingText(c.pending),
        lastApplied: null,
        hasActive: c.active != null,
        settable: c.settable,
        blocked: c.blocked,
    }));
}

/** 방침 선택지 — 현 · 군은 countyOptions, 군단은 corpsOptions. 건 방침이 있으면 끝에 「방침 거두기」. */
export function policyOptions(p: Policies, row: PolicyRow): CodeLabel[] {
    const base = row.scope === 'CORPS' ? p.corpsOptions : p.countyOptions;
    return row.hasActive ? [...base, { code: POLICY_WITHDRAW, label: '방침 거두기' }] : [...base];
}

/** 입력 몸통(`POST /api/commands/policy/set`) — 서버 parsePolicy 가 받는 필드 셋 그대로. */
export function policyBody(row: Pick<PolicyRow, 'scope' | 'targetId'>, policy: string): Readonly<Record<string, unknown>> {
    if (row.scope === 'COUNTY') return { scope: 'COUNTY', countyId: Number(row.targetId), policy };
    if (row.scope === 'COMMANDERY') return { scope: 'COMMANDERY', commanderyId: row.targetId, policy };
    return { scope: 'CORPS', orderId: row.targetId, policy };
}

// ── 공사 ─────────────────────────────────────────────────────────────

/** 자원 칩 글자(0 은 뺀다) — 「금 300 · 목재 120」. */
export function stockChips(stock: Stock | null | undefined): string[] {
    if (!stock) return [];
    return CAMPAIGN_RESOURCE_LABELS.filter(({ key }) => stock[key] > 0).map(({ key, label }) => `${label} ${stock[key]}`);
}

/**
 * 멈춘 사유. 서버가 모르는 멈춤 코드는 원문(영문 코드)을 그대로 돌려준다(`DomesticReader.stopText` else) —
 * 코드처럼 보이면 「멈춤(사유 준비 중)」(설계서 P-T01).
 */
export function stopText(text: string | null | undefined): string | null {
    if (!text) return null;
    return /^[A-Z][A-Z0-9_]*$/.test(text.trim()) ? '멈춤(사유 준비 중)' : text;
}

/** 성방(완공되면 「성방 허물기」 자리) — 서버 DomesticWork.FORTIFICATION. */
export const FORTIFICATION = 'FORTIFICATION';

export interface WorkRow {
    readonly countyId: number;
    readonly name: string;
    readonly commanderyName: string | null;
    readonly active: {
        readonly label: string;
        readonly percent: number;
        readonly remainingPhases: number;
        readonly stop: string | null;
        readonly nextBoundary: boolean;
        readonly remainingCost: string[];
    } | null;
    /** 완공 칩 — 「창고 · 200년 3월 상순」. */
    readonly completed: string[];
    readonly hasFortification: boolean;
    readonly warehouse: string[];
    readonly startableCount: number;
}

export function workRows(works: Works): WorkRow[] {
    return works.counties.map((c) => ({
        countyId: c.countyId,
        name: c.name,
        commanderyName: c.commanderyName,
        active: c.active ? {
            label: c.active.label,
            percent: Math.max(0, Math.min(100, c.active.percent)),
            remainingPhases: c.active.remainingPhases,
            stop: stopText(c.active.stopReasonText),
            nextBoundary: c.active.startsAtNextBoundary,
            remainingCost: stockChips(c.active.remainingCost),
        } : null,
        completed: c.completed.map((w) => (w.completedAt ? `${w.label} · ${phaseText(w.completedAt)}` : w.label)),
        hasFortification: c.completed.some((w) => w.work === FORTIFICATION),
        warehouse: stockChips(c.warehouse),
        startableCount: c.startable.filter((w) => w.available).length,
    }));
}

export interface WorkChoice {
    readonly work: string;
    readonly label: string;
    readonly available: boolean;
    readonly reason: string | null;
    readonly code: string | null;
    readonly cost: string[];
    readonly phases: number;
}

/** 공사 선택지 — 비용 · 예상 순 · 불가 사유를 카드에 보인다(옛 화면은 title 에만 있었다). */
export function workChoices(county: CountyWorks): WorkChoice[] {
    return county.startable.map((w) => ({
        work: w.work,
        label: w.label,
        available: w.available,
        reason: w.available ? null : w.blocked?.reason?.trim() || MISSING_REASON,
        code: w.available ? null : w.blocked?.code ?? null,
        cost: stockChips(w.cost),
        phases: w.estimatedPhases,
    }));
}

/** 입력 몸통(`POST /api/commands/work/start`) — 도로 · 보루는 화면이 고른 접경 · 칸(extra)을 더한다. */
export function workBody(countyId: number, work: string, extra?: Readonly<Record<string, unknown>> | null): Readonly<Record<string, unknown>> {
    return { countyId, work, ...(extra ?? {}) };
}
