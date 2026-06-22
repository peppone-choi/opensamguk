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
    npcModeText: '불가능',
    npcSummaryText: 'NPC 3명, 상성: 표준 사실',
    tournamentTermMinutes: 60,
    otherSettingText: '',
};

function renderGameInfo(global: Partial<FrontGlobalInfo>) {
    return render(<GameInfo global={{ ...baseGlobal, ...global }} constData={null} />);
}

describe('GameInfo parity render', () => {
    it('renders tournament term from front-info metadata', () => {
        const { rerender } = renderGameInfo({ turnterm: 3, tournamentTermMinutes: 5 });

        expect(screen.getByText('토너먼트: 경기당 5분')).toBeInTheDocument();

        rerender(<GameInfo global={{ ...baseGlobal, turnterm: 200, tournamentTermMinutes: 120 }} constData={null} />);
        expect(screen.getByText('토너먼트: 경기당 120분')).toBeInTheDocument();
    });

    it('renders the server-provided ten-day phase instead of a fixed turn label', () => {
        renderGameInfo({ turnPhase: 2, turnPhaseText: '중순' });

        expect(screen.getByText('현재: 200年 3月 중순 (60분 턴 서버)')).toBeInTheDocument();
        expect(screen.queryByText(/1순/)).not.toBeInTheDocument();
    });

    it('renders main settings from front-info metadata', () => {
        const { rerender } = renderGameInfo({ otherSettingText: '자율행동', generation: 7, npcModeText: '선택 생성' });

        expect(screen.getByText('기타 설정: 자율행동')).toBeInTheDocument();
        expect(screen.getByText(/7기/)).toBeInTheDocument();
        expect(screen.getByText('NPC선택: 선택 생성')).toBeInTheDocument();
        expect(screen.queryByText('기타 설정: 자동')).not.toBeInTheDocument();

        rerender(
            <GameInfo
                global={{ ...baseGlobal, otherSettingText: '', generation: undefined, npcModeText: '불가능' }}
                constData={null}
            />,
        );
        expect(screen.getByText('기타 설정:')).toBeInTheDocument();
        expect(screen.queryByText(/기\s+테스트 시나리오/)).not.toBeInTheDocument();
        expect(screen.queryByText('기타 설정: 자동')).not.toBeInTheDocument();
    });
});
