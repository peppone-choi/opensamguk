import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import TournamentPage from '@/app/game/tournament/page';
import { buildBracketRounds, buildStandingSections } from '@/app/game/tournament/view-model';
import type { TournamentBracketMatch, TournamentEntrant, TournamentResponse } from '@/types/game';

const apiMocks = vi.hoisted(() => ({
    frontInfo: vi.fn(),
    tournamentView: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <main>{children}</main>,
}));

vi.mock('@/components/CommandModal', () => ({
    default: () => <div>명령 모달</div>,
}));

vi.mock('@/lib/api', () => ({
    api: apiMocks,
}));

const entrants = [
    {
        generalId: 3,
        npc: 0,
        generalName: '조운',
        stage: 'MAIN',
        groupNo: 0,
        groupRank: 1,
        ability: 96,
        games: 3,
        win: 3,
        draw: 0,
        lose: 0,
        points: 9,
        goalDifference: 18,
        promoted: true,
    },
    {
        generalId: 1,
        npc: 0,
        generalName: '관우',
        stage: 'PRELIMINARY',
        groupNo: 0,
        groupRank: 1,
        ability: 97,
        games: 3,
        win: 2,
        draw: 1,
        lose: 0,
        points: 7,
        goalDifference: 12,
        promoted: true,
    },
] satisfies readonly TournamentEntrant[];

const bracket = [
    {
        round: 16,
        matchIdx: 1,
        leftGeneralId: 7,
        leftName: '황충',
        rightGeneralId: 8,
        rightName: '하후연',
        winnerGeneralId: null,
        winnerName: null,
    },
    {
        round: 16,
        matchIdx: 0,
        leftGeneralId: 1,
        leftName: '관우',
        rightGeneralId: 4,
        rightName: '여포',
        winnerGeneralId: 1,
        winnerName: '관우',
    },
] satisfies readonly TournamentBracketMatch[];

const response = {
    state: 7,
    tnmtType: 2,
    tnmtTypeText: '일기토',
    tnmtMsg: '16강 진행 중',
    turnTerm: 60,
    entrants,
    bracket,
    rankings: [
        {
            type: '전력전',
            rows: [{ rank: 1, generalName: '조운', nationName: '', value: 288 }],
        },
        { type: '통솔전', rows: [] },
        { type: '일기토', rows: [] },
        { type: '설전', rows: [] },
    ],
} satisfies TournamentResponse;

describe('Tournament route production contract', () => {
    beforeEach(() => {
        apiMocks.frontInfo.mockReset().mockRejectedValue(new Error('anonymous'));
        apiMocks.tournamentView.mockReset().mockResolvedValue(response);
    });

    it('renders non-empty PHP standing columns and semantic bracket fields', async () => {
        render(<TournamentPage />);

        await waitFor(() => expect(screen.getByText(/운영자 메세지/)).toHaveTextContent('운영자 메세지 : 16강 진행 중'));
        expect(screen.getByRole('heading', { name: '조별 본선 순위' })).toBeInTheDocument();
        expect(screen.getByRole('heading', { name: '조별 예선 순위' })).toBeInTheDocument();
        expect(screen.getAllByText('무력').length).toBeGreaterThan(0);
        expect(screen.getAllByText('조운').length).toBeGreaterThan(0);
        expect(screen.getAllByText('관우').length).toBeGreaterThan(0);
        expect(screen.getByText('여포')).toBeInTheDocument();
        expect(screen.getByText('경기당 1분')).toBeInTheDocument();
        expect(screen.getByText('288')).toBeInTheDocument();
        expect(screen.queryByText('참가자가 없습니다.')).not.toBeInTheDocument();
    });

    it('groups stages separately and orders bracket matches by match index', () => {
        const sections = buildStandingSections(entrants);
        expect(sections[0]?.stage).toBe('MAIN');
        expect(sections[0]?.groups[0]?.rows[0]?.generalName).toBe('조운');
        expect(sections[1]?.stage).toBe('PRELIMINARY');
        expect(sections[1]?.groups[0]?.rows[0]?.generalName).toBe('관우');

        const rounds = buildBracketRounds(bracket);
        expect(rounds[0]?.matches.map((match) => match.matchIdx)).toEqual([0, 1]);
        expect(rounds[0]?.matches[0]?.winnerName).toBe('관우');
    });
});
