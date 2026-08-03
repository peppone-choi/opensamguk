import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CharacterClaim from '@/components/game/CharacterClaim';
import type { FrontGlobalInfo } from '@/lib/types';

const apiMocks = vi.hoisted(() => ({
    claimable: vi.fn(),
    claim: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
    api: {
        claimable: apiMocks.claimable,
        claim: apiMocks.claim,
    },
}));

const global: FrontGlobalInfo = {
    year: 200,
    month: 1,
    turnterm: 10,
    scenario: '1010',
    scenarioText: '테스트 시나리오',
    generalCount: 1,
    nationCount: 1,
    cityCount: 1,
    npcCount: 1,
    serverId: 'pep',
    npcMode: 1,
    blockGeneralCreate: 1,
};

describe('CharacterClaim', () => {
    beforeEach(() => {
        apiMocks.claim.mockReset();
        apiMocks.claimable.mockReset().mockResolvedValue({
            result: true,
            hasGeneral: false,
            candidates: [
                {
                    generalId: 9,
                    name: '조조',
                    nationId: 1,
                    nationName: '위',
                    leadership: 80,
                    strength: 70,
                    intel: 90,
                    politics: 95,
                    charm: 85,
                    picture: null,
                    imageServer: 0,
                    special: null,
                    special2: null,
                    personal: null,
                },
            ],
        });
    });

    it('shows every five-stat value on possession cards and in the player hint', async () => {
        render(<CharacterClaim global={global} onClaimed={vi.fn()} />);

        expect(await screen.findByText('통 80')).toBeInTheDocument();
        expect(screen.getByText('무 70')).toBeInTheDocument();
        expect(screen.getByText('지 90')).toBeInTheDocument();
        expect(screen.getByText('정치 95')).toBeInTheDocument();
        expect(screen.getByText('매력 85')).toBeInTheDocument();
        expect(screen.getByText('선택한 장수의 능력치: 통솔 / 무력 / 지력 / 정치 / 매력')).toBeInTheDocument();
    });
});
