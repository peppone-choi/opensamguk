import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import GameInfo from '@/components/game/GameInfo';
import type { FrontGlobalInfo } from '@/lib/types';

const baseGlobal: FrontGlobalInfo = {
    year: 200,
    month: 3,
    turnterm: 60,
    scenario: 'che_1010',
    scenarioText: '테스트 시나리오',
    generalCount: 10,
    nationCount: 2,
    cityCount: 5,
    npcCount: 3,
};

function renderGameInfo(global: Partial<FrontGlobalInfo>) {
    return render(<GameInfo global={{ ...baseGlobal, ...global }} constData={null} />);
}

describe('GameInfo parity render', () => {
    it('clamps tournament term through the legacy calcTournamentTerm helper', () => {
        const { rerender } = renderGameInfo({ turnterm: 3 });

        expect(screen.getByText('토너먼트: 경기당 5분')).toBeInTheDocument();

        rerender(<GameInfo global={{ ...baseGlobal, turnterm: 200 }} constData={null} />);
        expect(screen.getByText('토너먼트: 경기당 120분')).toBeInTheDocument();
    });

    it('renders 기타 설정 from legacy autorunUser instead of a baked 자동 label', () => {
        const { rerender } = renderGameInfo({
            autorunUser: { limit_minutes: 120, options: { develop: 1 } },
        });

        expect(screen.getByText('기타 설정: 자율행동')).toBeInTheDocument();
        expect(screen.queryByText('기타 설정: 자동')).not.toBeInTheDocument();

        rerender(
            <GameInfo
                global={{ ...baseGlobal, autorunUser: { limit_minutes: 0, options: { develop: 1 } } }}
                constData={null}
            />,
        );
        expect(screen.getByText('기타 설정:')).toBeInTheDocument();
        expect(screen.queryByText('기타 설정: 자동')).not.toBeInTheDocument();
    });
});
