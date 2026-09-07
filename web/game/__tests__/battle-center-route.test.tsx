import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BattleCenterPage from '@/app/game/battle-center/page';

const apiMocks = vi.hoisted(() => ({
  nationGeneralList: vi.fn(),
  frontInfo: vi.fn(),
  generalLog: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
  default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/game/GeneralBasicCard', () => ({
  default: ({ general, nation }: { general: { name: string | null; politics?: number; charm?: number }; nation: { name: string } | null }) => (
    <section data-testid="general-basic-card">
      {general.name} / {nation?.name ?? '재야'} / 정치 {general.politics} / 매력 {general.charm}
    </section>
  ),
}));

vi.mock('@/app/game/simulator/page', () => ({
  default: () => <div>전투 시뮬레이터</div>,
}));

vi.mock('@/lib/api', () => ({
  api: apiMocks,
}));

const columns = [
  'no',
  'name',
  'nation',
  'npc',
  'injury',
  'leadership',
  'strength',
  'intel',
  'politics',
  'charm',
  'explevel',
  'dedlevel',
  'gold',
  'rice',
  'killturn',
  'picture',
  'imgsvr',
  'age',
  'specialDomestic',
  'specialWar',
  'personal',
  'belong',
  'troop',
  'city',
  'experience',
  'dedication',
  'officerLevel',
  'crewtype',
  'crew',
  'train',
  'atmos',
  'turntime',
  'recentWar',
  'horse',
  'weapon',
  'book',
  'item',
  'warnum',
  'killnum',
  'deathnum',
  'killcrew',
  'deathcrew',
  'firenum',
  'officerLevelText',
  'lbonus',
  'honorText',
  'dedLevelText',
  'bill',
];

function row(no: number, name: string, turntime: string, recentWar: string | null, warnum: number) {
  const values: Record<string, unknown> = {
    no,
    name,
    nation: 1,
    npc: 0,
    injury: 0,
    leadership: 70 + no,
    strength: 60 + no,
    intel: 50 + no,
    politics: 40 + no,
    charm: 30 + no,
    explevel: 1,
    dedlevel: 1,
    gold: 1000,
    rice: 1000,
    killturn: 30,
    picture: 'default.jpg',
    imgsvr: 0,
    age: 20,
    specialDomestic: 'None',
    specialWar: 'None',
    personal: 'che_중립',
    belong: 1,
    troop: 0,
    city: 1,
    experience: 1000,
    dedication: 1000,
    officerLevel: 1,
    crewtype: 1100,
    crew: 100,
    train: 80,
    atmos: 70,
    turntime,
    recentWar,
    horse: 'None',
    weapon: 'None',
    book: 'None',
    item: 'None',
    warnum,
    killnum: 1,
    deathnum: 0,
    killcrew: 10,
    deathcrew: 5,
    firenum: 2,
    officerLevelText: '장군',
    lbonus: 0,
    honorText: '평범',
    dedLevelText: '평범',
    bill: 100,
  };
  return columns.map((key) => values[key]);
}

function generalListResponse(overrides: Partial<{ permission: number; list: unknown[][] }> = {}) {
  return {
    result: true,
    permission: overrides.permission ?? 1,
    column: columns,
    list: overrides.list ?? [row(1, '장료', '2026-05-30 08:00:00', '2026-05-30 07:00:00', 9), row(2, '관우', '2026-05-30 07:00:00', '2026-05-30 09:00:00', 3)],
    troops: [],
    env: { year: 200, month: 1, turnterm: 60, turntime: '2026-05-30 10:00:00', killturn: 80 },
    myGeneralID: 1,
  };
}

describe('BattleCenter route', () => {
  beforeEach(() => {
    apiMocks.nationGeneralList.mockReset();
    apiMocks.frontInfo.mockReset();
    apiMocks.generalLog.mockReset();
    apiMocks.nationGeneralList.mockResolvedValue(generalListResponse());
    apiMocks.frontInfo.mockResolvedValue({
      nation: { id: 1, name: '후한왕조', color: '#333333', level: 1, gold: 0, rice: 0, tech: 0, capitalCityId: 1 },
    });
    apiMocks.generalLog.mockImplementation((generalId: number, reqType: string) =>
      Promise.resolve({
        result: true,
        generalID: generalId,
        reqType,
        log: { 20: `${reqType}-${generalId}` },
      }),
    );
  });

  it('renders 감찰부 from the nation general-list, not the simulator alias', async () => {
    render(<BattleCenterPage />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '감찰부' })).toBeInTheDocument());

    expect(screen.queryByText('전투 시뮬레이터')).not.toBeInTheDocument();
    expect(apiMocks.nationGeneralList).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('general-basic-card')).toHaveTextContent('장료 / 후한왕조 / 정치 41 / 매력 31');
    expect(screen.getByRole('option', { name: '장료(08:00)' })).toBeInTheDocument();

    for (const type of ['generalHistory', 'battleDetail', 'battleResult', 'generalAction']) {
      await waitFor(() => expect(apiMocks.generalLog).toHaveBeenCalledWith(1, type));
      await waitFor(() => expect(screen.getByText(`${type}-1`)).toBeInTheDocument());
    }

    // 우측 레일 — 정렬·대상 장수·기록 구획 앵커 4종(라벨 verbatim)
    const rail = screen.getByRole('complementary', { name: '감찰 대상' });
    expect(within(rail).getByLabelText('정렬')).toBeInTheDocument();
    expect(within(rail).getByLabelText('대상 장수')).toBeInTheDocument();
    for (const [type, title] of [['generalHistory', '장수 열전'], ['battleDetail', '전투 기록'], ['battleResult', '전투 결과'], ['generalAction', '개인 기록']]) {
      expect(within(rail).getByRole('link', { name: new RegExp(title) })).toHaveAttribute('href', `#bc-${type}`);
      expect(document.getElementById(`bc-${type}`)).not.toBeNull();
    }

    fireEvent.click(screen.getByRole('button', { name: '다음 ▶' }));
    await waitFor(() => expect(screen.getByTestId('general-basic-card')).toHaveTextContent('관우 / 후한왕조'));
    expect(apiMocks.generalLog).toHaveBeenCalledWith(2, 'generalHistory');
  });

  it('resorts target options with legacy sort keys', async () => {
    render(<BattleCenterPage />);

    await waitFor(() => expect(screen.getByTestId('general-basic-card')).toHaveTextContent('장료 / 후한왕조'));

    fireEvent.change(screen.getByLabelText('정렬'), { target: { value: 'recent_war' } });

    const select = screen.getByLabelText('대상 장수');
    const options = within(select).getAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual([expect.stringContaining('관우'), expect.stringContaining('장료')]);
    await waitFor(() => expect(screen.getByTestId('general-basic-card')).toHaveTextContent('관우 / 후한왕조'));
  });

  it('handles permission, empty, and fetch errors without fake data', async () => {
    apiMocks.nationGeneralList.mockResolvedValueOnce(generalListResponse({ permission: 0 }));
    const { rerender } = render(<BattleCenterPage />);

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('권한이 부족합니다.'));
    expect(apiMocks.generalLog).not.toHaveBeenCalled();

    apiMocks.nationGeneralList.mockResolvedValueOnce(generalListResponse({ list: [] }));
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.getByText('감찰할 장수가 없습니다.')).toBeInTheDocument());

    apiMocks.nationGeneralList.mockRejectedValueOnce(new Error('401: Unauthorized'));
    rerender(<BattleCenterPage />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('401: Unauthorized'));
  });
});
