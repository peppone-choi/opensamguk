import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import GameAdminPage from '@/app/game/admin/page';

const state = vi.hoisted(() => ({ tab: 'generals', role: 'ADMIN' }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(`tab=${state.tab}`),
  useRouter: () => ({ replace: vi.fn() }),
}));
vi.mock('@/lib/auth-context', () => ({
  useAuthOptional: () => ({ loading: false, user: { role: state.role } }),
}));
vi.mock('@/components/Shell', () => ({ default: ({ children }: { children: ReactNode }) => <main>{children}</main> }));
vi.mock('@/components/admin/GameSettingsPanel', () => ({ default: () => <div>설정 패널</div> }));
vi.mock('@/components/admin/GeneralModerationPanel', () => ({ default: () => <div>장수 조치 패널</div> }));
vi.mock('@/components/admin/NationStatsPanel', () => ({ default: () => <div>국가 통계 패널</div> }));
vi.mock('@/components/admin/GeneralLogPanel', () => ({ default: () => <div>로그 패널</div> }));
vi.mock('@/components/admin/DiplomacyAllPanel', () => ({ default: () => <div>외교 패널</div> }));
vi.mock('@/components/admin/ServerStatusPanel', () => ({ default: () => <div>서버 상태 패널</div> }));

describe('게임 관리자 허브', () => {
  it('keeps common moderation tools reachable for administrators', () => {
    state.role = 'ADMIN';
    state.tab = 'generals';
    render(<GameAdminPage />);
    expect(screen.getByText('장수 조치 패널')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '게임 설정' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '로그정보' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: '토너먼트 관리' })).not.toBeInTheDocument();
  });

  it('does not render administrator tools for ordinary accounts', () => {
    state.role = 'USER';
    render(<GameAdminPage />);
    expect(screen.getByRole('alert')).toHaveTextContent('관리자 권한이 필요합니다.');
    expect(screen.queryByText('장수 조치 패널')).not.toBeInTheDocument();
  });
});
