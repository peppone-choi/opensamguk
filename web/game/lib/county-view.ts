// 현 상세(P-T02)의 보기 모델 — 지금 있는 조회(`/api/county/{id}` 특산 · `/api/warehouses` · `/api/policies` · `/api/works`)로
// 채울 수 있는 칸만. React 없음.
//
// 서버 대기(계약판 K4-04 `GET /api/counties/{cityId}`): 7지표(호구 · 전답 · 시장 · 치안 · 방비 · 성벽 · 민심 — 부 엔진 지표) ·
// 등급 · 수비군 · 이 현의 인물 · 계절 사건. 삼모 `/api/city/{id}` 는 쓰지 않는다(삼모 필드 · 보안 의심, 설계서 P-T02).

import { UNOWNED_NATION_NAME } from '@opensamguk/ui';
import type { County, Warehouse } from './campaign-reads';

export interface CountyNation {
    readonly label: string;
    /** 세력색. 무주면 null(색 네모 없음). */
    readonly color: string | null;
}

/** 소속 칩 — 주인 없음은 공용 상수 「무주」(K1 #1070 `UNOWNED_NATION_NAME`). */
export function countyNation(nation: { readonly id: number; readonly name: string | null; readonly color: string | null } | null | undefined): CountyNation {
    if (!nation || nation.id <= 0) return { label: UNOWNED_NATION_NAME, color: null };
    return { label: nation.name ?? UNOWNED_NATION_NAME, color: nation.color };
}

export interface SpecialtyRow {
    readonly resource: string;
    readonly label: string;
    /** 「월 12」 · 창고가 깨져 모르면 「읽지 못함」. */
    readonly monthly: string;
    /** 원장 설계값(있으면 「설계 월 20」). */
    readonly ledger: string | null;
    readonly zero: boolean;
}

export function specialtyRows(county: County | null): SpecialtyRow[] {
    return (county?.specialties ?? []).map((s) => ({
        resource: s.resource,
        label: s.label,
        monthly: s.monthly == null ? '읽지 못함' : `월 ${s.monthly}`,
        ledger: s.ledgerMonthly == null ? null : `설계 월 ${s.ledgerMonthly}`,
        zero: s.monthly === 0,
    }));
}

/**
 * 이번 달 특산이 0 인 이유 한 줄(서버 주석: 주인 없음 · 보급 끊김 · 창고 없음이면 0). 원인을 서버가 따로 주지 않아
 * 화면이 가진 값(소속 · 창고 · 이어짐)으로 가려 보고, 셋 다 아니면 가르지 않는다.
 */
export function specialtyZeroReason(owned: boolean, warehouse: Pick<Warehouse, 'supplied'> | null): string {
    if (!owned) return '이번 달 0 — 주인이 없는 현입니다.';
    if (!warehouse) return '이번 달 0 — 이 현에 창고가 없습니다.';
    if (!warehouse.supplied) return '이번 달 0 — 수도와 끊긴 현입니다.';
    return '이번 달 0 — 주인 없음 · 보급 끊김 · 창고 없음 중 하나입니다.';
}

/** 서버 대기 칸 문구(K4-04 전까지 StatusView waiting 으로 그린다). */
export const COUNTY_WAITING = {
    indicators: { title: '형편 — 준비 중', body: '호구 · 전답 · 시장 · 치안 · 방비 · 성벽 · 민심 일곱 지표는 서버가 곧 줍니다.' },
    garrison: { title: '수비군 — 준비 중', body: '성을 지키는 병력 · 훈련 · 사기는 서버가 곧 줍니다.' },
    people: { title: '이 현의 인물 — 준비 중', body: '이 현에 있는 인물 목록은 서버가 곧 줍니다.' },
} as const;

/** 최근 사건이 없을 때 — 기록 화면(P-H01, K5)과 같은 문장. 시야 밖과 없음을 가르지 않는다. */
export const COUNTY_RECORDS_EMPTY = '이 현에는 아직 보일 기록이 없습니다';
