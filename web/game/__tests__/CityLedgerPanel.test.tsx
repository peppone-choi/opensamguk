import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import CityLedgerPanel from '@/components/v2/CityLedgerPanel';

// OPENSAM-155 (v2 R6) — 도시 원장 패널.
//
// 고정하는 것은 "느린 응답이 최신 도시의 값을 덮어쓰지 않는다"이다. 이게 깨지면 화면이 "5번 도시"라고
// 써 놓고 3번 도시의 금을 보여준다 — 유저는 틀린 잔액을 보고 수송·징병을 결정한다.

const fetchCityLedger = vi.hoisted(() => vi.fn());

vi.mock('@/lib/v2/cityLedger', () => ({
    fetchCityLedger,
    formatLedgerNumber: (value: number) => value.toLocaleString('ko-KR'),
}));

describe('CityLedgerPanel', () => {
    beforeEach(() => fetchCityLedger.mockReset());

    it('도시의 금·병량·도시병사를 표시한다', async () => {
        fetchCityLedger.mockResolvedValue({ cityId: 3, gold: 1200, rice: 800, garrison: 5000 });

        render(<CityLedgerPanel cityId={3} />);

        await waitFor(() => expect(screen.getByTestId('v2-ledger-gold')).toHaveTextContent('1,200'));
        expect(screen.getByTestId('v2-ledger-rice')).toHaveTextContent('800');
        expect(screen.getByTestId('v2-ledger-garrison')).toHaveTextContent('5,000');
    });

    it('도시 ID가 없으면 조회하지 않는다', () => {
        render(<CityLedgerPanel cityId={Number('')} />);

        expect(fetchCityLedger).not.toHaveBeenCalled();
    });

    it('늦게 도착한 이전 도시의 응답은 최신 도시의 값을 덮어쓰지 않는다', async () => {
        const pending = new Map<number, (v: unknown) => void>();
        fetchCityLedger.mockImplementation(
            (cityId: number) => new Promise(resolve => pending.set(cityId, resolve)),
        );

        const { rerender } = render(<CityLedgerPanel cityId={3} />);
        rerender(<CityLedgerPanel cityId={5} />);

        // 최신 도시(5)가 먼저 도착하고, 옛 도시(3)의 응답이 뒤늦게 온다.
        pending.get(5)!({ cityId: 5, gold: 50, rice: 60, garrison: 70 });
        await waitFor(() => expect(screen.getByTestId('v2-ledger-gold')).toHaveTextContent('50'));
        pending.get(3)!({ cityId: 3, gold: 999, rice: 999, garrison: 999 });

        await waitFor(() => expect(screen.getByTestId('v2-ledger-gold')).toHaveTextContent('50'));
        expect(screen.getByTestId('v2-ledger-gold')).not.toHaveTextContent('999');
    });
});
