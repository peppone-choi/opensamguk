import { describe, expect, it } from 'vitest';
import { parseWarRoomMapView, warRoomMapSearch } from '@/lib/war-room-map-view';

const q = (search: string) => new URLSearchParams(search);

describe('작전실 지도 주소 보기(?view=…&focus=…)', () => {
    it('보기 수준 셋과 城 id를 읽는다', () => {
        expect(parseWarRoomMapView(q('view=ju'))).toEqual({ level: 'ju', focusCityId: null });
        expect(parseWarRoomMapView(q('view=commandery'))).toEqual({ level: 'commandery', focusCityId: null });
        expect(parseWarRoomMapView(q('view=county&focus=12'))).toEqual({ level: 'county', focusCityId: 12 });
    });

    it('모르는 값은 버린다(기본 보기) — 대소문자 · 빈 값 · 0 · 음수 · 소수 · 글자 · 앞 0', () => {
        for (const search of ['view=JU', 'view=', 'view=province', '']) expect(parseWarRoomMapView(q(search)).level).toBeNull();
        for (const focus of ['0', '-1', '1.5', 'abc', '01', '']) expect(parseWarRoomMapView(q(`focus=${focus}`)).focusCityId).toBeNull();
        expect(parseWarRoomMapView(null)).toEqual({ level: null, focusCityId: null });
    });

    it('바로가기 주소의 검색 부분을 만든다', () => {
        expect(warRoomMapSearch('ju')).toBe('?view=ju');
        expect(warRoomMapSearch('county', 12)).toBe('?view=county&focus=12');
        expect(parseWarRoomMapView(q(warRoomMapSearch('county', 12).slice(1)))).toEqual({ level: 'county', focusCityId: 12 });
    });
});
