// 현 상세 읽기(계약판 K4-04 `GET /api/counties/{cityId}`, C10) — 미리 연결. React 없음.
//
// 계약판 행(125 · 321)에 이름이 적힌 칸만 받는다: 지금은 수비군 `garrison{troops, training, morale}` 하나를 화면에 잇는다.
// 7지표의 키 · trend 모양, grade, peopleHere[] 원소, front? 는 행에 모양이 없어 받지 않는다(지어내지 않는다, CEO 10-05).
// C10 경로가 main 에 들어오기 전에는 부르지 않는다 — 없는 경로를 불러 404 · 콘솔 오류를 남기지 않게.

/** C10 이 `/api/counties/{cityId}` 를 main 에 넣으면 true 로 켜는 PR 을 낸다. */
export const COUNTY_DETAIL_READY = false;

export interface CountyGarrison {
    readonly troops: number;
    readonly training: number;
    readonly morale: number;
}

/** 현 상세 응답 중 이 화면이 지금 받는 칸. 나머지 칸은 행이 확정되면 더한다. */
export interface CountyDetailRead {
    readonly status: string;
    readonly cityId: number;
    /** 시야 밖이면 null(계약판 「시야 밖은 null」). */
    readonly garrison?: CountyGarrison | null;
}

export function countyDetailPath(generalId: number, cityId: number): string {
    return `/api/counties/${cityId}?generalId=${generalId}`;
}

export interface GarrisonRow {
    readonly label: string;
    readonly value: string;
}

/** 수비군 세 줄(보드 V31K4County 「수비군 — 병력 · 훈련 · 사기」). 읽기가 없거나 시야 밖이면 null → 화면은 서버 대기 · 안 보임. */
export function garrisonRows(detail: CountyDetailRead | null | undefined): readonly GarrisonRow[] | null {
    const g = detail?.status === 'READY' ? detail.garrison ?? null : null;
    if (!g) return null;
    const n = (v: number) => v.toLocaleString('ko-KR');
    return [
        { label: '병력', value: n(g.troops) },
        { label: '훈련', value: n(g.training) },
        { label: '사기', value: n(g.morale) },
    ];
}
