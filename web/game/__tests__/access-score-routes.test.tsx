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

  it('keeps hidden injury unknown on the public ranking instead of healthy zero', async () => {
    apiMocks.generalsList.mockResolvedValue([{ ...publicGeneral(1, '비공개부상', 0), injury: null }]);
    render(<RankingsGeneralsPage />);
    await waitFor(() => expect(screen.getAllByText('비공개부상').length).toBeGreaterThan(0));
    const row = screen.getAllByRole('row')[1];
    expect(within(row).getByText('부상 정보 미확인')).toBeInTheDocument();
    expect(row.textContent).not.toMatch(/NaN|부상 없음|건강/);
    expect(row.querySelector('.stat--wounded')).toBeNull();
    expect(within(row).getAllByText('70')).toHaveLength(5);
  });

  it('keeps hidden injury unknown on the searchable public list', async () => {
    apiMocks.generalsList.mockResolvedValue([{ ...publicGeneral(1, '비공개부상', 0), injury: null }]);
    render(<GeneralsPage />);
    await waitFor(() => expect(screen.getByText('비공개부상')).toBeInTheDocument());
    expect(within(screen.getAllByRole('row')[1]).getByText('부상 정보 미확인')).toBeInTheDocument();
  });

  it('keeps hidden injury unknown on the nation roster', async () => {
    apiMocks.myGenerals.mockResolvedValue({ result: true, nationId: 1,
      generals: [{ ...myGeneral(0), injury: null }] });
    render(<MyGeneralsPage />);
    await waitFor(() => expect(screen.getByText('조조')).toBeInTheDocument());
    const row = screen.getByText('조조').closest('tr')!;
    expect(within(row).getByText('부상 정보 미확인')).toBeInTheDocument();
    expect(row.textContent).not.toMatch(/NaN|부상 없음|건강/);
    expect(row.querySelector('.stat--wounded')).toBeNull();
  });

  it('preserves the actual nonzero injury adjustment for an authorized ranking row', async () => {
    apiMocks.generalsList.mockResolvedValue([{ ...publicGeneral(1, '허용부상', 0), injury: 20 }]);
    render(<RankingsGeneralsPage />);
    await waitFor(() => expect(screen.getAllByText('허용부상').length).toBeGreaterThan(0));
    const row = screen.getAllByRole('row')[1];
    expect(row.querySelectorAll('.stat--wounded')).toHaveLength(3);
    expect(within(row).getAllByText('56')).toHaveLength(3);
    expect(within(row).queryByText('부상 정보 미확인')).toBeNull();
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
