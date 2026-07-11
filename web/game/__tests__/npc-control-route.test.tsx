import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import NpcControlPage from '@/app/game/npc-control/page';

const apiMocks = vi.hoisted(() => ({
    frontInfo: vi.fn(),
    npcPolicy: vi.fn(),
    updateNpcPolicy: vi.fn(),
    commandResult: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/lib/api', () => ({
    api: {
        frontInfo: apiMocks.frontInfo,
        npcPolicy: apiMocks.npcPolicy,
        updateNpcPolicy: apiMocks.updateNpcPolicy,
        commandResult: apiMocks.commandResult,
    },
    isIntakeDenied: (out: { status: string }) => out.status === 'BLOCKED' || out.status === 'UNKNOWN',
    isIntakeQueued: (out: { status: string }) => out.status === 'AVAILABLE',
}));

const npcPolicyResponse = {
    result: true,
    nationId: 1,
    defaultNationPolicy: {
        reqNationGold: 10000,
        reqNationRice: 12000,
        reqHumanWarUrgentGold: 0,
        reqHumanWarUrgentRice: 0,
        reqHumanWarRecommandGold: 0,
        reqHumanWarRecommandRice: 0,
        reqHumanDevelGold: 10000,
        reqHumanDevelRice: 10000,
        reqNPCWarGold: 0,
        reqNPCWarRice: 0,
        reqNPCDevelGold: 0,
        reqNPCDevelRice: 500,
        minimumResourceActionAmount: 1000,
        maximumResourceActionAmount: 10000,
        minWarCrew: 1500,
        minNPCRecruitCityPopulation: 50000,
        safeRecruitCityPopulationRatio: 0.5,
        minNPCWarLeadership: 40,
        properWarTrainAtmos: 90,
        cureThreshold: 10,
        CombatForce: {},
        SupportForce: [],
        DevelopForce: [],
    },
    currentNationPolicy: {
        reqNationGold: 15000,
        reqNationRice: 12000,
        reqHumanWarUrgentGold: 0,
        reqHumanWarUrgentRice: 0,
        reqHumanWarRecommandGold: 0,
        reqHumanWarRecommandRice: 0,
        reqHumanDevelGold: 10000,
        reqHumanDevelRice: 10000,
        reqNPCWarGold: 0,
        reqNPCWarRice: 0,
        reqNPCDevelGold: 0,
        reqNPCDevelRice: 500,
        minimumResourceActionAmount: 1000,
        maximumResourceActionAmount: 10000,
        minWarCrew: 1500,
        minNPCRecruitCityPopulation: 50000,
        safeRecruitCityPopulationRatio: 0.5,
        minNPCWarLeadership: 40,
        properWarTrainAtmos: 90,
        cureThreshold: 10,
        CombatForce: {},
        SupportForce: [],
        DevelopForce: [],
    },
    zeroPolicy: {
        reqHumanWarUrgentGold: 48000,
        reqHumanWarUrgentRice: 48000,
        reqNPCWarGold: 30000,
        reqNPCWarRice: 30000,
        reqNPCDevelGold: 9000,
    },
    defaultNationPriority: ['천도', '선전포고'],
    currentNationPriority: ['선전포고'],
    availableNationPriorityItems: ['천도', '선전포고'],
    defaultGeneralActionPriority: ['출병', '일반내정'],
    currentGeneralActionPriority: ['출병', '일반내정'],
    availableGeneralActionPriorityItems: ['출병', '일반내정', '귀환'],
    lastSetters: {
        policy: { setter: null, date: null },
        nation: { setter: null, date: null },
        general: { setter: null, date: null },
    },
    defaultStatNPCMax: 75,
    defaultStatMax: 80,
};

describe('NpcControlPage live editor', () => {
    beforeEach(() => {
        apiMocks.frontInfo.mockReset().mockResolvedValue({
            general: { hasGeneral: true, nationId: 1, permission: 2 },
        });
        apiMocks.npcPolicy.mockReset().mockResolvedValue(npcPolicyResponse);
        apiMocks.updateNpcPolicy.mockReset().mockResolvedValue({ status: 'AVAILABLE', requestId: 'req-npc' });
        apiMocks.commandResult.mockReset().mockResolvedValue({ status: 'RESOLVED', requestId: 'req-npc', ok: true, type: 'npcPolicyUpdate', result: {} });
    });

    it('resets and reverts nation policy values without saving', async () => {
        render(<NpcControlPage />);
        const input = await screen.findByRole('spinbutton', { name: '국가 권장 금' });
        expect(input).toHaveValue(15000);

        fireEvent.click(screen.getAllByRole('button', { name: '초깃값으로' })[0]);
        expect(input).toHaveValue(10000);
        expect(screen.getByRole('status')).toHaveTextContent('서버 초깃값을 적용했습니다.설정 버튼을 누르면 반영됩니다.');

        fireEvent.click(screen.getAllByRole('button', { name: '이전값으로' })[0]);
        expect(input).toHaveValue(15000);
    });

    it('saves only after the daemon result resolves ok', async () => {
        render(<NpcControlPage />);
        const input = await screen.findByRole('spinbutton', { name: '국가 권장 금' });
        fireEvent.change(input, { target: { value: '22200' } });

        fireEvent.click(screen.getAllByRole('button', { name: '설정' })[0]);

        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('NPC 정책이 반영되었습니다.'));
        expect(apiMocks.updateNpcPolicy).toHaveBeenCalledWith(expect.objectContaining({
            type: 'nationPolicy',
            data: expect.objectContaining({ reqNationGold: 22200 }),
        }));
        expect(apiMocks.commandResult).toHaveBeenCalledWith('req-npc');
    });

    it('surfaces backend deny instead of showing fake success', async () => {
        apiMocks.commandResult.mockResolvedValue({
            status: 'RESOLVED',
            requestId: 'req-npc',
            ok: false,
            type: 'npcPolicyUpdate',
            reason: '출병 명령은 일반내정 명령보다 먼저여야 합니다.',
            result: {},
        });
        render(<NpcControlPage />);
        await screen.findByText('NPC 일반턴 우선순위');

        const generalPanel = screen.getByText('NPC 일반턴 우선순위').closest('section');
        expect(generalPanel).not.toBeNull();
        fireEvent.click(within(generalPanel as HTMLElement).getByRole('button', { name: '일반내정 위로' }));
        fireEvent.click(within(generalPanel as HTMLElement).getByRole('button', { name: '설정' }));

        await waitFor(() => {
            expect(screen.getByRole('status')).toHaveTextContent('설정하지 못했습니다: 출병 명령은 일반내정 명령보다 먼저여야 합니다.');
        });
    });
});
