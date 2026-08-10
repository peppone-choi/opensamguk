import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SelectRecruitField from '@/components/command/SelectRecruitField';
import type { FrontInfoResponse, GameConstResponse } from '@/lib/types';

const mocks = vi.hoisted(() => ({
    gameConst: vi.fn(),
    frontInfo: vi.fn(),
    recruitAvailability: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
    api: {
        gameConst: mocks.gameConst,
        frontInfo: mocks.frontInfo,
        recruitAvailability: mocks.recruitAvailability,
    },
}));

const constData = {
    gameUnitConst: [
        { id: 1100, armType: 1, name: '보병', attack: 10, defence: 8, speed: 5, avoid: 3, magicCoef: 0, cost: 4, rice: 2, info: ['기본 병과'] },
        { id: 1000, armType: 1, name: '성벽', attack: 1, defence: 20, speed: 0, avoid: 0, magicCoef: 0, cost: 0, rice: 0, info: ['성벽입니다.', '생성할 수 없습니다.'] },
    ],
} as unknown as GameConstResponse;

const frontInfo = {
    general: {
        generalId: 10,
        leadership: 80,
        leadershipBonus: 0,
        lbonus: 0,
        crew: 0,
        crewTypeId: 0,
        crewTypeName: '-',
        gold: 1000,
    },
} as unknown as FrontInfoResponse;

describe('SelectRecruitField', () => {
    it('uses server availability rather than Korean display text', async () => {
        mocks.gameConst.mockResolvedValueOnce(constData);
        mocks.frontInfo.mockResolvedValueOnce(frontInfo);
        mocks.recruitAvailability.mockResolvedValueOnce({
            result: true,
            unitSet: 'che',
            crewTypes: [
                { crewType: 1100, available: true, reason: null },
                { crewType: 1000, available: false, reason: '현재 선택할 수 없는 병종입니다.' },
            ],
        });

        render(<SelectRecruitField onChange={vi.fn()} />);

        await waitFor(() => expect(screen.getByRole('button', { name: /보병/ })).toBeInTheDocument());
        expect(screen.queryByText('성벽')).not.toBeInTheDocument();

        fireEvent.click(screen.getByLabelText('전체 병과 보기'));

        const blocked = screen.getByRole('button', { name: /성벽/ });
        expect(blocked).toBeDisabled();
        expect(blocked).toHaveTextContent('현재 선택할 수 없는 병종입니다.');
    });
});
