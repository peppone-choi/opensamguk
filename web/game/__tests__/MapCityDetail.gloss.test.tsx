import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import MapCityDetail from '@/components/game/MapCityDetail';
import type { CityDetailResponse, MapPreviewCity } from '@/lib/types';

vi.mock('@/lib/api', () => ({ api: { city: vi.fn(() => new Promise(() => {})) } }));

const city = (id: number, name: string, nameCh: string, displayName: string): MapPreviewCity => ({
    id, name, nameCh, displayName, level: 10, nationId: 0, x: 0, y: 0, state: 0, supply: true,
} as MapPreviewCity);

const detail = (id: number): CityDetailResponse => ({
    id, name: '', nameCh: null, level: 10, levelName: '영', region: 2, regionName: '중원', nationId: 0, visible: false,
    population: null, populationMax: null, agriculture: null, agricultureMax: null, commerce: null, commerceMax: null,
    security: null, securityMax: null, defense: null, defenseMax: null, wall: null, wallMax: null, trust: null,
    trade: null, supplyState: 1, frontState: 0, officers: null,
});

// #838: 영천군 「양성현」 둘 — 서버 꼬리 「(阳城)」를 작은 繁體 병기로 바꿔 단다.
describe('MapCityDetail 머리글 漢字 병기', () => {
    it('같은 郡 안 同音 縣은 이름 뒤에 繁體 병기 span 이 붙는다', () => {
        const { container } = render(
            <MapCityDetail city={city(134, '양성(潁川郡)#134', '阳城县', '영천군 양성현(阳城)')}
                nationName="공백지" nationColor="#000000" isCurrent={false} cityDetail={detail(134)} />,
        );
        const header = container.querySelector('.mcd-city-name');
        expect(header).toHaveTextContent('【중원 | 영】 영천군 양성현陽城');
        expect(header).not.toHaveTextContent('(阳城)');
        expect(header?.querySelector('.os-place-gloss')).toHaveTextContent('陽城');
    });

    it('병기 대상이 아닌 城은 이름 그대로다', () => {
        const { container } = render(
            <MapCityDetail city={city(1039, '와구(九江郡)', '渦口', '와구(渦口)')}
                nationName="공백지" nationColor="#000000" isCurrent={false} cityDetail={detail(1039)} />,
        );
        const header = container.querySelector('.mcd-city-name');
        expect(header).toHaveTextContent('와구(渦口)');
        expect(header?.querySelector('.os-place-gloss')).toBeNull();
    });
});
