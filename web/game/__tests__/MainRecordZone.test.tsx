import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import MainRecordZone from '@/components/game/MainRecordZone';

describe('MainRecordZone', () => {
    it('renders the legacy PageFront three-feed record zone', () => {
        render(
            <MainRecordZone
                recentRecord={{
                    global: [[11, '<C>●</>1월:장수 동향 새 기록', 181, 1, 2, '중순']],
                    general: [[12, '<C>●</>1월:개인 새 기록', 181, 1, 2, '중순']],
                    history: [[9, '<C>●</>181년 1월:중원 새 기록', 181, 1, 2, '중순']],
                    flushGlobal: 0,
                    flushGeneral: 0,
                    flushHistory: 0,
                }}
            />,
        );

        expect(screen.getByRole('region', { name: '장수 동향' })).toHaveTextContent('장수 동향 새 기록');
        expect(screen.getByRole('region', { name: '개인 기록' })).toHaveTextContent('개인 새 기록');
        expect(screen.getByRole('region', { name: '중원 정세' })).toHaveTextContent('중원 새 기록');
        expect(screen.queryByText('181년 1월 중순')).not.toBeInTheDocument();
        expect(screen.getByRole('region', { name: '중원 정세' })).toHaveTextContent('●181년 1월:중원 새 기록');
    });

    it('keeps rendering during mixed-version deploys that still return an empty array', () => {
        render(<MainRecordZone recentRecord={[]} />);

        expect(screen.getByRole('region', { name: '장수 동향' })).toHaveTextContent('장수 동향');
        expect(screen.getByRole('region', { name: '개인 기록' })).toHaveTextContent('개인 기록');
        expect(screen.getByRole('region', { name: '중원 정세' })).toHaveTextContent('중원 정세');
    });
});
