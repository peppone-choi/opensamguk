import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import TurnCatchUpControl, { type AdminCatchUpInfo } from '../components/admin/TurnCatchUpControl';

const active: AdminCatchUpInfo = {
    active: true, multiplier: 4, backlogSeconds: 72000,
    remainingSeconds: 24000, etaAt: '2026-09-27T06:40:00Z',
    initialBacklogSeconds: 72000, recoveredSeconds: 0,
};

describe('TurnCatchUpControl', () => {
    afterEach(() => vi.unstubAllGlobals());

    it('posts a 4 to 2 change and shows the refreshed ETA immediately', async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
        vi.stubGlobal('fetch', fetchMock);
        const onChanged = vi.fn().mockResolvedValue(undefined);
        const { rerender } = render(<TurnCatchUpControl catchUp={active} serverId="pep" onChanged={onChanged} />);

        // 지금과 같은 배속이면 사유와 함께 잠겨 있다(설계서 §3.4 C6).
        expect(screen.getByRole('button', { name: '배속 적용' })).toHaveAttribute('data-reason', '지금과 같은 배속입니다');
        fireEvent.click(screen.getByRole('radio', { name: '2배속' }));
        fireEvent.click(screen.getByRole('button', { name: '배속 적용' }));
        await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
        expect(fetchMock).toHaveBeenCalledWith(
            '/api/proxy/admin/turn-daemon/catch-up?serverId=pep',
            expect.objectContaining({ method: 'POST', body: '{"multiplier":2}' }),
        );

        rerender(<TurnCatchUpControl catchUp={{ ...active, multiplier: 2, remainingSeconds: 72000,
            etaAt: '2026-09-28T20:00:00Z' }} serverId="pep" onChanged={onChanged} />);
        expect(screen.getByText('2배속', { selector: 'dd' })).toBeInTheDocument();
        expect(screen.getByText('남은 회복 시간').nextElementSibling).toHaveTextContent('20시간 0분');
    });
});
