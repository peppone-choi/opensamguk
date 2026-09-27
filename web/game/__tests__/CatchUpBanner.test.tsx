import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import CatchUpBanner from '../components/CatchUpBanner';

describe('CatchUpBanner', () => {
    it('shows speed and Korean-time ETA in the shared visible status', () => {
        render(<CatchUpBanner catchUp={{
            active: true, multiplier: 4, backlogSeconds: 72000,
            remainingSeconds: 24000, etaAt: '2026-09-27T06:40:00Z',
        }} />);
        expect(screen.getByRole('status').textContent).toContain('4배속');
        expect(screen.getByRole('status').textContent).toContain('한국 시간');
    });

    it('disappears when recovery ends', () => {
        render(<CatchUpBanner catchUp={{
            active: false, multiplier: 2, backlogSeconds: 0,
            remainingSeconds: 0, etaAt: null,
        }} />);
        expect(screen.queryByRole('status')).toBeNull();
    });
});
