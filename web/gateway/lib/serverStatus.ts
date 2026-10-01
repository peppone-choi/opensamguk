// 로그인 · 로비 「서버 현황」 자료 — 지도 미리보기(`/api/server-map/{id}` = game-api /api/map/preview) 한 번으로
// 세력 현황과 천하 정세 이름 풀이를 함께 만든다(계약판 K5-11: 삼모 랭킹 `/api/rankings/kingdoms` 대신).
// 세력 순서는 보이는 값(현 수)으로 정한다 — 보이지 않는 병력으로 줄 세우지 않는다(설계서 NS7).
import type { EventNames } from '@opensamguk/ui';
import type { MapData } from '@/components/MapPreview';

export interface NationStatusRow {
    readonly nationId: number;
    readonly name: string;
    readonly color: string;
    /** 소유한 현(城) 수. */
    readonly counties: number;
}

const HEX_COLOR = /^#[0-9a-f]{6}$/i;

/** 소유 현이 하나 이상인 세력만, 현 수 내림차순(같으면 이름순). 주인 없는 城(nationId ≤ 0)은 세지 않는다. */
export function nationStatusRows(preview: Pick<MapData, 'cities' | 'nations'>): NationStatusRow[] {
    const counts = new Map<number, number>();
    for (const city of preview.cities) {
        if (city.nationId > 0) counts.set(city.nationId, (counts.get(city.nationId) ?? 0) + 1);
    }
    return preview.nations
        .filter((nation) => nation.id > 0 && (counts.get(nation.id) ?? 0) > 0)
        .map((nation) => ({
            nationId: nation.id,
            name: nation.name,
            color: HEX_COLOR.test(nation.color) ? nation.color : '#8e8879',
            counties: counts.get(nation.id) ?? 0,
        }))
        .sort((a, b) => b.counties - a.counties || a.name.localeCompare(b.name, 'ko'));
}

/**
 * 「200년 3월 중순」. 순 글자는 서버가 준 것만 쓴다. 해 · 달이 안 왔으면 짐작하지 않고 「확인 중」으로 적는다
 * (셸 머리줄 규칙) — 「undefined년 undefined월」을 찍지 않는다.
 */
export function previewDate(preview: Pick<MapData, 'year' | 'month' | 'turnPhaseText'>): string {
    if (!Number.isFinite(preview.year) || !Number.isFinite(preview.month)) return '확인 중';
    return `${preview.year}년 ${preview.month}월${preview.turnPhaseText ? ` ${preview.turnPhaseText}` : ''}`;
}

/** 「pep 1기 · 200년 3월 중순」 — 서버 칩 옆 · 로비 지도 아래 캡션. */
export function previewCaption(serverLabel: string, preview: Pick<MapData, 'year' | 'month' | 'turnPhaseText'>): string {
    return `${serverLabel} · ${previewDate(preview)}`;
}

/** 미리보기의 현 · 세력 이름으로 사건 refs 를 푼다. 미리보기가 없으면 모두 모름(문장은 「어느 현」). */
export function previewNames(preview: Pick<MapData, 'cities' | 'nations'> | null | undefined): EventNames {
    const cities = new Map((preview?.cities ?? []).map((city) => [city.id, city.displayName ?? city.name]));
    const nations = new Map((preview?.nations ?? []).filter((nation) => nation.id > 0).map((nation) => [nation.id, nation.name]));
    return {
        city: (id) => cities.get(id),
        nation: (id) => nations.get(id),
    };
}

export function serverLabel(server: { readonly name: string; readonly generation?: number }): string {
    return server.generation != null ? `${server.name} ${server.generation}기` : server.name;
}
