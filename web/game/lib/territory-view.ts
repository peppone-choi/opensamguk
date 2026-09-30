// 영지 — 배치 · 방침 · 공사(P-T01)의 보기 모델. `/api/posts` · `/api/policies` · `/api/works` 를 화면 줄로 바꾼다. React 없음.
//
// 가능 여부는 서버 값(available · placeable · settable · blocked)만 쓴다. 불가 항목은 숨기지 않고 사유와 함께 보인다
// (옛 DomesticPanels 는 available=false 자리를 사유 없이 뺐다 — 설계서 P-T01).

import type { TargetCandidate } from '@opensamguk/ui';
import type { Blocked, PlacementCard, PostOption, Posts } from './campaign-reads';

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
