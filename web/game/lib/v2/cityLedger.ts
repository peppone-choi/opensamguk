// OPENSAM-155 (v2 R6) — 도시 원장 read 계약. read 전용이며 mutation 경로가 없다.
//
// 컴포넌트 파일이 아니라 여기에 두는 이유: `'use client'` 모듈에서 비-컴포넌트 값을 내보내면
// 클라이언트 경계 프록시를 거치며 import 가 깨진다(빌드 경고 → 런타임 undefined). 계약은 평범한
// 모듈에 두고 컴포넌트는 컴포넌트만 내보낸다.

import { api } from '../api';

export interface CityLedgerView {
    cityId: number;
    gold: number;
    rice: number;
    garrison: number;
}

/** 한 도시. 원장 행이 없는 도시는 404가 아니라 0/0/0으로 온다(엔진 `V2CityLedgerEntry.EMPTY`와 동일). */
export const fetchCityLedger = (cityId: number) => api.get<CityLedgerView>(`/api/v2/city-ledger/${cityId}`);

/** 월드 전체, city_id 오름차순. */
export const fetchCityLedgerList = () => api.get<{ entries: CityLedgerView[] }>('/api/v2/city-ledger');

/** 천 단위 구분만 넣는다 — 값은 서버가 준 그대로. */
export const formatLedgerNumber = (value: number) => value.toLocaleString('ko-KR');
