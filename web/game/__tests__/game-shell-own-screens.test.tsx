import { render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import GameShell from '../components/GameShell';
import type { NavScreen } from '../lib/nav31';

// 묶음(NAV31) 밖 화면이 제 하위 탭을 준다(게임 관리 `admin?tab=` — 레일에 묶음이 없다).
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/admin', useSearchParams: () => new URLSearchParams('tab=people') }));
vi.mock('next/link', () => ({ default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => <a href={href} {...rest}>{children}</a> }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep', generalId: null, loading: false, error: null, frontInfo: null }) }));

const SCREENS: readonly NavScreen[] = [
    { label: '세력 개요', path: 'admin?tab=nations', built: true },
    { label: '인물 조치', path: 'admin?tab=people', built: true },
];

test('screens 를 주면 그 탭을 그리고 screenOn 을 켠다(주소는 서버 경로 + 쿼리)', () => {
    render(<GameShell title="게임 관리" requiresHwiha={false} screens={SCREENS} screenOn="인물 조치"><p>본문</p></GameShell>);
    const nav = screen.getByRole('navigation', { name: '하위 화면' });
    const links = within(nav).getAllByRole('link');
    expect(links.map((a) => a.textContent)).toEqual(['세력 개요', '인물 조치']);
    expect(within(nav).getByRole('link', { name: '인물 조치' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: '세력 개요' })).not.toHaveAttribute('aria-current');
    expect(within(nav).getByRole('link', { name: '세력 개요' }).getAttribute('href')).toMatch(/admin\?tab=nations$/);
});

test('screens 가 없으면 묶음 밖 화면(admin)은 하위 탭이 없다 — 다른 화면 불변', () => {
    render(<GameShell title="게임 관리" requiresHwiha={false}><p>본문</p></GameShell>);
    expect(screen.queryByRole('navigation', { name: '하위 화면' })).toBeNull();
    expect(screen.getByRole('heading', { level: 2, name: '게임 관리' })).toBeInTheDocument();
});
