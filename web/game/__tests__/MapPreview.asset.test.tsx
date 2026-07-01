import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';

import MapPreview from '../../gateway/components/MapPreview';

const MAP_FIXTURE = {
    serverName: '테스트섭',
    year: 200,
    month: 5,
    turnPhase: 1,
    turnPhaseText: '상순',
    mapCode: 'miniche_b',
    width: 700,
    height: 500,
    cities: [
        { id: 11, name: '낙양', level: 8, nationId: 0, x: 300, y: 250, state: 0, supply: true, isCapital: false },
    ],
    nations: [],
};

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

function getCanvas(): HTMLElement {
    return document.querySelector('.map-preview-canvas') as HTMLElement;
}

beforeEach(() => {
    vi.stubGlobal(
        'fetch',
        vi.fn(async () => jsonResponse(MAP_FIXTURE)),
    );
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
        configurable: true,
        get() {
            return this.classList?.contains('map-preview-canvas') ? 700 : 0;
        },
    });
});

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('MapPreview — miniche 계열 CDN 폴백', () => {
    it('miniche_b는 che 배경과 miniche_road.png를 쓴다', async () => {
        render(<MapPreview mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());

        const bg = document.querySelector('.map-bg') as HTMLImageElement | null;
        const road = document.querySelector('.map-road') as HTMLImageElement | null;
        expect(bg).toBeTruthy();
        expect(road).toBeTruthy();
        expect(bg!.src).toContain('/game/map/che/bg_summer.jpg');
        expect(road!.src).toContain('/game/map/che/miniche_road.png');
    });
});
