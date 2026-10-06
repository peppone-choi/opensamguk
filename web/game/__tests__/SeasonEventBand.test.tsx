// 현 상세 계절 사건 띠(P-K07, K8 부품 · 자리는 K4 CountyScreen) — 그 현에 사건이 있을 때만 그린다.
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import SeasonEventBand from '@/components/season/SeasonEventBand';
import type { SeasonEventsState } from '@/lib/season-events';

const READY: SeasonEventsState = {
    kind: 'ready',
    occurrences: [
        { countyId: 11, kind: 'FLOOD', effect: { population: -10, displaced: 4 } },
        { countyId: 12, kind: 'PLAGUE', effect: { trust: -1 } },
        { countyId: 11, kind: 'TYPHOON', effect: { trust: -1 } },
    ],
};

describe('SeasonEventBand', () => {
    it('사건 없음 → 띠 없음: 읽기 없음 · 셈하지 못함 · 이 현 사건 0 · 현을 모름', () => {
        for (const [countyId, events] of [[11, undefined], [11, { kind: 'unavailable' }], [13, READY], [null, READY]] as const) {
            const { container, unmount } = render(<SeasonEventBand countyId={countyId} events={events as SeasonEventsState | undefined} />);
            expect(container.firstChild).toBeNull();
            unmount();
        }
    });

    it('이 현의 사건만 한 줄씩 — 사건 이름 · 방향, 모르는 종류는 「계절 사건」, 수치 없음', () => {
        render(<SeasonEventBand countyId={11} events={READY} />);
        const band = screen.getByRole('status', { name: '이 현의 계절 사건' });
        expect(band).toHaveTextContent('홍수▼ 호구▲ 유민');
        expect(band).toHaveTextContent('계절 사건▼ 민심');
        expect(band).not.toHaveTextContent('역병'); // 다른 현 사건
        expect(band.textContent).not.toMatch(/\d/);
    });
});
