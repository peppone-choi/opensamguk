// 현 상세(P-T02)의 보기 모델. 지금 있는 읽기(지도 미리보기 · front-info · /api/county · policies · works · warehouses · visibility)를
// 화면 칸으로 묶는다. 縣 상세 읽기(K4-04 `GET /api/counties/{cityId}` — 7지표 · 등급 · 수비군 · 이 현의 사람)가 오기 전에는
// 그 칸을 「서버 대기」로 둔다(K0 10-03). React 없음.
import { UNOWNED_NATION_NAME, type GaugeTone } from '@opensamguk/ui';
import type { CountyPolicy, CountyWorks, Policies, Stock, Visibility, VisionTier, Warehouses, Works } from './campaign-reads';
import type { FrontCityInfo, MapPreviewCity, MapPreviewNation } from './types';

/** 형편 7지표 — 부 엔진 지표(DomesticDesign.kt). 시세는 쓰지 않는다. */
export interface IndicatorRow {
    readonly label: string;
    readonly value: number;
    readonly max: number;
    readonly tone?: GaugeTone;
}

/**
 * 7지표 — 지금은 내 장수가 선 현(front-info city)만 실제 값이 있다. 다른 현이면 null(화면은 「서버 대기」).
 * 민심은 상한 100(작전실 縣 카드와 같다), 50 아래는 경고색.
 */
export function indicatorRows(city: FrontCityInfo | null | undefined, cityId: number): readonly IndicatorRow[] | null {
    if (!city || city.id !== cityId) return null;
    return [
        { label: '호구', value: city.population, max: city.populationMax },
        { label: '전답', value: city.agriculture, max: city.agricultureMax },
        { label: '시장', value: city.commerce, max: city.commerceMax },
        { label: '치안', value: city.security, max: city.securityMax },
        { label: '민심', value: city.trust, max: 100, tone: city.trust < 50 ? 'rust' : 'moss' },
        { label: '방비', value: city.defense, max: city.defenseMax, tone: 'bronze' },
        { label: '성벽', value: city.wall, max: city.wallMax, tone: 'bronze' },
    ];
}

export interface CountyHead {
    readonly name: string;
    readonly commanderyName: string | null;
    readonly ownerName: string;
    readonly ownerColor: string | null;
    /** 우리 세력 현인가(소속 비교 — 시야 · 입력 권한의 바탕). */
    readonly mine: boolean;
    readonly isCapital: boolean;
    readonly isSeat: boolean;
    /** 우리 현인데 보급이 끊겼다(지도 미리보기 supply). 남의 현은 보이지 않으니 false. */
    readonly isolated: boolean;
    readonly here: boolean;
}

/** 머리 — 이름 · 군 · 소속 깃발 · 수도 · 치소 · 고립 · 지금 여기. 무주는 공용 상수(K1 #1070). */
export function countyHead(city: MapPreviewCity, nations: readonly MapPreviewNation[], me: { readonly nationId: number | null; readonly cityId: number | null }): CountyHead {
    const owner = city.nationId > 0 ? nations.find((n) => n.id === city.nationId) ?? null : null;
    const mine = me.nationId != null && me.nationId > 0 && city.nationId === me.nationId;
    return {
        name: city.name,
        commanderyName: city.commanderyName ?? null,
        ownerName: city.nationId > 0 ? owner?.name ?? '어느 세력' : UNOWNED_NATION_NAME,
        ownerColor: owner?.color ?? null,
        mine,
        isCapital: city.isCapital,
        isSeat: city.isCommanderySeat === true,
        isolated: mine && !city.supply,
        here: me.cityId === city.id,
    };
}

export interface CountyVision {
    /** null = 시야 읽기에서 이 현의 군을 찾지 못했다(모름 — FULL 로 짓지 않는다). */
    readonly tier: VisionTier | null;
    readonly ageTurns: number | null;
    /** 첩보 흐름 대상(`?target=commandery:<id>`) — 흐름 주소 형식에 맞을 때만. */
    readonly commanderyId: string | null;
}

const FLOW_ID = /^[A-Za-z0-9_-]{1,64}$/;

/** 시야 — 우리 현은 늘 FULL. 남의 현은 이 현 군의 시야 등급(이름 대조 — 작전실 commanderyOfCity 와 같은 방식). */
export function countyVision(visibility: Visibility | null | undefined, commanderyName: string | null, mine: boolean): CountyVision {
    const row = visibility?.status === 'READY' && commanderyName ? visibility.commanderies?.find((c) => c.name === commanderyName) ?? null : null;
    return {
        tier: mine ? 'FULL' : row?.tier ?? null,
        ageTurns: !mine && row?.tier === 'INTEL' ? row.ageTurns ?? null : null,
        commanderyId: row && FLOW_ID.test(row.id) ? row.id : null,
    };
}

/** 읽기 하나의 상태 — 빈자리 · 「진행 중인 공사 없음」 같은 「없음」은 READY 일 때만 말한다(읽는 중 · 실패를 없음으로 그리지 않는다, #1222 리뷰). */
export type ReadState = 'loading' | 'error' | 'unavailable' | 'ready';

export function readState(read: { readonly data: { readonly status: string } | null; readonly error: string | null }): ReadState {
    if (read.error) return 'error';
    if (!read.data) return 'loading';
    return read.data.status === 'READY' ? 'ready' : 'unavailable';
}

/** 다스림 — 이 현의 방침 줄(우리 현만 서버가 준다). */
export function countyPolicy(policies: Policies | null | undefined, cityId: number): CountyPolicy | null {
    return policies?.status === 'READY' ? policies.counties.find((c) => c.countyId === cityId) ?? null : null;
}

/** 공사 — 이 현의 공사 줄(우리 현만 서버가 준다). */
export function countyWorks(works: Works | null | undefined, cityId: number): CountyWorks | null {
    return works?.status === 'READY' ? works.counties.find((c) => c.countyId === cityId) ?? null : null;
}

export type CountyStock =
    | { readonly kind: 'stock'; readonly stock: Stock; readonly supplied: boolean }
    | { readonly kind: 'none' }
    | { readonly kind: 'hidden' }
    | { readonly kind: 'unknown' };

/** 이 현 창고 — 우리 현이면 창고망 읽기에서(없으면 「창고 없음」), 남의 현이면 안 보임. 읽기 실패 · 대기는 모름. */
export function countyStock(warehouses: Warehouses | null | undefined, cityId: number, mine: boolean): CountyStock {
    if (!mine) return { kind: 'hidden' };
    if (warehouses?.status !== 'READY') return { kind: 'unknown' };
    const row = warehouses.warehouses.find((w) => w.cityId === cityId);
    return row ? { kind: 'stock', stock: row.stock, supplied: row.supplied } : { kind: 'none' };
}

/**
 * 특산 한 칩 — 우리 현은 이번 달 실제 몫(monthly)과 설계값(ledgerMonthly), 남의 현은 설계값만(사용자 결정 D40 · 시야 계약 09-23).
 * 실제 몫은 그 현의 호구 · 시장 · 전답 · 보급 · 창고에서 나오는 실시간 값이라 시야 밖이다. 서버가 아직 시야와 무관하게 주므로 화면이 먼저 막는다. 모르면 「?」.
 */
export function specialtyText(s: { readonly label: string; readonly monthly: number | null; readonly ledgerMonthly?: number | null }, mine: boolean): string {
    const fmt = (n: number) => n.toLocaleString('ko-KR');
    const design = s.ledgerMonthly == null ? null : fmt(s.ledgerMonthly);
    if (!mine) return `${s.label} 설계 ${design ?? '?'}/월`;
    const now = s.monthly == null ? '?' : fmt(s.monthly);
    return design != null && design !== now ? `${s.label} ${now}/월 · 설계 ${design}` : `${s.label} ${now}/월`;
}

/** 경로의 `[cityId]` 조각 → 양의 정수만. 아니면 null(화면은 「이 현을 찾을 수 없습니다」). */
export function parseCityId(raw: string | string[] | undefined | null): number | null {
    const v = Array.isArray(raw) ? raw[0] : raw;
    return v && /^[1-9]\d{0,8}$/.test(v) ? Number(v) : null;
}
