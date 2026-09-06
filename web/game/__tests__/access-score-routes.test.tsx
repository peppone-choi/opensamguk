import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import GeneralsPage from '@/app/game/generals/page';
import MyGeneralsPage from '@/app/game/my-generals/page';
import RankingsGeneralsPage from '@/app/game/rankings/generals/page';

const apiMocks = vi.hoisted(() => ({
  generalsList: vi.fn(),
  myGenerals: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameTable', () => ({
  default: ({ headers, rows }: { headers: React.ReactNode[]; rows: React.ReactNode[][] }) => (
    <table>
      <thead>
        <tr>{headers.map((header, index) => <th key={index}>{header}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((row, rowIndex) => (
          <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>
        ))}
      </tbody>
    </table>
  ),
}));

vi.mock('@/lib/api', () => ({
  api: {
    generalsList: apiMocks.generalsList,
    myGenerals: apiMocks.myGenerals,
  },
}));

function publicGeneral(generalId: number, name: string, refreshScoreTotal: number) {
  return {
    generalId,
    name,
    nationId: 1,
    nationName: '위',
    nationColor: '#c62828',
    npc: 0,
    officerLevel: 1,
    officerLevelText: '일반',
    leadership: 70,
    strength: 70,
    intel: 70,
    politics: 70,
    charm: 70,
    explevel: 0,
    honorText: '전무',
    dedlevel: 0,
    dedLevelText: '무품관',
    bill: 400,
    crew: 0,
    cityName: '허창',
    picture: null,
    imageServer: 0,
    age: 20,
    personalText: '의리',
    specialDomesticText: '-',
    specialWarText: '-',
    injury: 0,
    lbonus: 0,
    killturn: 30,
    refreshScoreTotal,
  };
}

function myGeneral(refreshScoreTotal: number) {
  return {
    generalId: 1,
    name: '조조',
    cityId: 1,
    officerLevel: 12,
    leadership: 90,
    strength: 80,
    intel: 95,
    politics: 96,
    charm: 97,
    crew: 1000,
    npcState: 0,
    mine: true,
    picture: null,
    imageServer: 0,
    officerLevelText: '황제',
    dedLevelText: '1품관',
    honorText: '영웅',
    bill: 1000,
    gold: 5000,
    rice: 3000,
    personalText: '정복',
    specialDomesticText: '-',
    specialWarText: '-',
    belong: 10,
    injury: 0,
    lbonus: 14,
    dedication: 10000,
    experience: 50000,
    personal: 'che_정복',
    special: 'None',
    special2: 'None',
    ownerName: null,
    refreshScoreTotal,
  };
}

describe('general access score routes', () => {
  beforeEach(() => {
    apiMocks.generalsList.mockReset();
    apiMocks.myGenerals.mockReset();
  });

  it('sorts the legacy ranking page by rounded total score descending', async () => {
    apiMocks.generalsList.mockResolvedValue([
      publicGeneral(1, '저점', 54),
      publicGeneral(2, '고점', 106),
    ]);

    render(<RankingsGeneralsPage />);

    // 상위 3 시상대(壹貳參)가 이름을 한 번 더 그린다 — 표 행은 아래에서 따로 본다.
    await waitFor(() => expect(screen.getAllByText('고점').length).toBeGreaterThan(0));
    const rows = screen.getAllByRole('row');
    expect(within(rows[1]).getByText('고점')).toBeInTheDocument();
    expect(rows[1].lastElementChild).toHaveTextContent('110【보통】');
  });

  it('shows the rounded score on the searchable general list', async () => {
    apiMocks.generalsList.mockResolvedValue([
      publicGeneral(1, '저점', 54),
      publicGeneral(2, '고점', 106),
    ]);

    render(<GeneralsPage />);

    await waitFor(() => expect(screen.getByText('고점')).toBeInTheDocument());
    expect(screen.getByRole('columnheader', { name: /벌점/ })).toBeInTheDocument();
    const rows = screen.getAllByRole('row');
    expect(within(rows[1]).getByText('고점')).toBeInTheDocument();
    expect(rows[1].lastElementChild).toHaveTextContent('110【보통】');
  });

  it('shows the unrounded score and grade on the nation roster', async () => {
    apiMocks.myGenerals.mockResolvedValue({ result: true, nationId: 1, generals: [myGeneral(88)] });

    render(<MyGeneralsPage />);

    await waitFor(() => expect(screen.getByText('조조')).toBeInTheDocument());
    expect(screen.getByRole('columnheader', { name: '벌점' })).toBeInTheDocument();
    const row = screen.getByText('조조').closest('tr');
    expect(row?.lastElementChild).toHaveTextContent('88(무관심)');
  });
});
