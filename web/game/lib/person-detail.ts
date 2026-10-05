// 인물 상세 읽기(계약판 K4-13 · 335행 `GET /api/people/{대상 장수 id}`, C10) — 미리 짓기(D124). React 없음.
//
// 받는 칸은 C9 계약판 「K4 생산자 후속 타입 · ACL 합의」(C10 `2026-10-05-c10-k4-dto-accepted-fields.md` §K4-13, K4 회신 반영)의 모양 그대로다.
// 늘 부른다: 서버 경로가 아직 없거나(404) 실패하면 받은 값이 없으니 화면은 지금 읽기(front-info · 부 · 배치)로만 그리고,
// 서버가 main 에 들어오는 순간 별도 PR 없이 같은 세력 · 다른 세력 인물도 보인다.
// 관직(C5) · 녹봉 · 보물 · 생몰 · 배치(C1)는 첫 판에 늘 null 이라 받지 않는다(서버 대기 그대로).
import type { Aptitudes, Bond, FiveStats } from './campaign-reads';

/** 서버 관계 판정. 사적인 칸(위치 · 결속 · 부상 · 부 카드)은 SELF · 직접 RETINUE 만 연다 — 같은 세력이라고 열리지 않는다. */
export type DetailRelation = 'SELF' | 'RETINUE' | 'SAME_NATION' | 'OTHER' | 'UNKNOWN';

/** 내가 직접 거느린 부 카드(녹봉이 아니다). 없는 카드는 null. */
export interface DetailRetinueCard {
    readonly retainerId: number;
    readonly loyalty: number;
    readonly cost: number | null;
    readonly roleLabel: string | null;
    readonly taskLabel: string | null;
    readonly departureOrder: number | null;
}

/** 인물 상세 응답 중 이 화면이 받는 칸. 값을 모르거나 권한 밖이면 null 이고, 이유는 `unavailableReasons` 에 JSON pointer 로 온다. */
export interface PersonDetailRead {
    readonly status: string;
    readonly relation?: DetailRelation | null;
    readonly generalId: number;
    readonly name?: string | null;
    readonly portrait?: { readonly picture: string | null; readonly imageServer: number } | null;
    /** 양수 세력. 재야는 null(권한 밖이면 이유가 따로 온다). */
    readonly affiliation?: { readonly nationId: number; readonly name: string; readonly color: string } | null;
    readonly stats?: FiveStats | null;
    readonly aptitudes?: Aptitudes | null;
    readonly role?: 'LORD' | 'RETAINER' | 'FREE' | null;
    readonly lordGeneralId?: number | null;
    readonly location?: { readonly cityId: number; readonly name: string } | null;
    /** 부(retinue) BondDto 와 같은 모양 하나 — DirectoryBond(kind, targetId)와 섞지 않는다. */
    readonly bonds?: readonly Bond[] | null;
    /** 부상 여부(0–100 부상률이 0 보다 큰가). 회복까지 남은 순이 아니다. */
    readonly injured?: boolean | null;
    readonly retinue?: DetailRetinueCard | null;
    readonly unavailableReasons?: Readonly<Record<string, string>> | null;
}

/** 대상은 경로, 보는 사람(내 장수)은 `generalId` 쿼리 — 현 상세(K4-04)와 같은 꼴. */
export function personDetailPath(generalId: number, targetGeneralId: number): string {
    return `/api/people/${targetGeneralId}?generalId=${generalId}`;
}

const RELATIONS: readonly DetailRelation[] = ['SELF', 'RETINUE', 'SAME_NATION', 'OTHER', 'UNKNOWN'];

/**
 * 받은 상세가 이 화면의 그 인물인가 — READY · PARTIAL 이고, 대상 id 가 같고, 관계를 아는 것만 쓴다.
 * 관계가 UNKNOWN · 낯선 값이면 null(지금 읽기로만 그린다). 이름 없는 응답도 쓰지 않는다(이름을 짓지 않는다).
 */
export function usableDetail(detail: PersonDetailRead | null | undefined, generalId: number): PersonDetailRead | null {
    if (!detail || (detail.status !== 'READY' && detail.status !== 'PARTIAL')) return null;
    if (detail.generalId !== generalId || !detail.name?.trim()) return null;
    const relation = detail.relation;
    if (!relation || !RELATIONS.includes(relation) || relation === 'UNKNOWN') return null;
    return detail;
}
