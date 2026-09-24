import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MyPage from '@/app/game/my/page';
import { DEPT_GROUPS } from '@/lib/dept-menu-config';

const apiMocks = vi.hoisted(() => ({ frontInfo: vi.fn(), myPage: vi.fn() }));

vi.mock('@/components/Shell', () => ({
  default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/game/GeneralBasicCard', () => ({
  default: () => <section data-testid="general-basic-card" />,
}));
vi.mock('@/components/game/MyInfoLogPanel', () => ({
  default: ({ generalId }: { generalId: number }) => <section data-testid="my-info-log-panel">general:{generalId}</section>,
}));
vi.mock('@/lib/api', () => ({ api: apiMocks }));

const frontInfo = {
  result: true,
  global: { serverId: 'pep' },
  general: {
    hasGeneral: true, generalId: 77, name: '코덱스', nationId: 1,
    officerLevel: 1, permission: 0, showSecret: false,
    leadership: 70, strength: 60, intel: 80, injury: 0,
    gold: 100, rice: 200, crew: 300, cityId: 11, defenceTrain: 80,
  },
  nation: { id: 1, name: '후한왕조', color: '#333333', level: 1, gold: 0, rice: 0, tech: 0, capitalCityId: 1 },
  city: null,
  recentRecord: { history: [], global: [], general: [], flushHistory: 0, flushGlobal: 0, flushGeneral: 0 },
};
const myPage = {
  generalId: 77, name: '코덱스', nationId: 1, nationName: '후한왕조',
  cityId: 11, cityName: '업', leadership: 70, strength: 60, intel: 80,
  politics: 55, charm: 45, injury: 0, gold: 100, rice: 200, crew: 300,
  train: 90, atmos: 80,
  instantActions: { instantRetreatPossible: true, dieOnPrestartPossible: true },
  items: [{ type: 'horse', label: '명마', code: 'che_명마_15_적토마', name: '적토마(+15)', droppable: true }],
};

describe('내 정보 제품 화면', () => {
  beforeEach(() => {
    apiMocks.frontInfo.mockReset().mockResolvedValue(frontInfo);
    apiMocks.myPage.mockReset().mockResolvedValue(myPage);
  });

  it('부서 메뉴에서 공통 내 정보와 세력 장수로 연결한다', () => {
    const routes = DEPT_GROUPS.flatMap((group) => group.entries);
    expect(routes).toContainEqual({ kind: 'route', label: '내 정보', href: '/game/my' });
    expect(routes).toContainEqual({ kind: 'route', label: '세력 장수', href: '/game/my-generals' });
  });

  it('장수 정보와 기록을 유지하고 휘하 편성으로 연결한다', async () => {
    render(<MyPage />);
    await waitFor(() => expect(screen.getByRole('heading', { name: '내 정보' })).toBeInTheDocument());
    expect(screen.getByTestId('general-basic-card')).toBeInTheDocument();
    expect(screen.getByTestId('my-info-log-panel')).toHaveTextContent('general:77');
    expect(screen.getAllByText('후한왕조').length).toBeGreaterThan(0);
    expect(screen.getByText('90 / 80')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '휘하 편성' })).toHaveAttribute('href', '/game/pep/hwiha/retinue');
  });

  it('삼모 즉시 행동은 응답에 있어도 제품 화면에 노출하지 않는다', async () => {
    render(<MyPage />);
    await screen.findByRole('heading', { name: '내 정보' });
    expect(screen.queryByRole('button', { name: '아이템 버리기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '즉시 접경귀환' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '개전 전 장수 삭제' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '휴가' })).not.toBeInTheDocument();
  });
});
