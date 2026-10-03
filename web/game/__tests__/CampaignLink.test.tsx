import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// 캠페인 링크는 서버를 모르는 동안(쿠키를 읽기 전 첫 렌더) 미리 불러오지 않는다 — 서버 없는 /game/<화면> 은
// 운영 게이트웨이가 서버 이름으로 읽어 502 를 낸다(K10 pep 측정 2026-09-30, 모바일 5건).
const session = vi.hoisted(() => ({ serverId: undefined as string | undefined }));

vi.mock('next/link', () => ({
    default: ({ href, prefetch, children, ...rest }: { href: string; prefetch?: boolean; children: React.ReactNode }) => (
        <a href={href} data-prefetch={String(prefetch)} {...rest}>{children}</a>
    ),
}));
vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => ({
        serverId: session.serverId, frontInfo: null, loading: false, error: null, generalId: 7,
        gameDate: '200년 3월 중순', refresh: () => {},
    }),
}));
vi.mock('../lib/campaign-reads', () => ({ useRenown: () => null }));
const nav = vi.hoisted(() => ({ pathname: '/game/retinue/yuedan' }));
vi.mock('next/navigation', () => ({ usePathname: () => nav.pathname, useSearchParams: () => new URLSearchParams() }));

import CampaignLink from '../components/campaign/CampaignLink';
import GameShell from '../components/GameShell';

afterEach(() => { session.serverId = undefined; });

describe('CampaignLink', () => {
    it('does not prefetch the server-less address before the server is known', () => {
        render(<CampaignLink slug="retinue/yuedan">월단평</CampaignLink>);
        const link = screen.getByRole('link', { name: '월단평' });
        expect(link.getAttribute('href')).toBe('/game/retinue/yuedan');
        expect(link.getAttribute('data-prefetch')).toBe('false');
    });

    it('puts the server in the address and leaves prefetch on once the server is known', () => {
        session.serverId = 'pep';
        render(<CampaignLink slug="retinue" query="?person=3">부</CampaignLink>);
        const link = screen.getByRole('link', { name: '부' });
        expect(link.getAttribute('href')).toBe('/game/pep/retinue?person=3');
        expect(link.getAttribute('data-prefetch')).toBe('undefined');
    });
});

describe('GameShell 하위 탭 (모바일 미리 불러오기)', () => {
    it('서버를 모르면 미리 불러오지 않고, 서버를 알면 모든 탭 주소에 서버가 들어간다', () => {
        const { unmount } = render(<GameShell title="월단평"><p>본문</p></GameShell>);
        const before = screen.getByRole('navigation', { name: '하위 화면' }).querySelectorAll('a');
        expect(before.length).toBeGreaterThan(0);
        for (const a of before) expect(a.getAttribute('data-prefetch'), a.textContent ?? '').toBe('false');
        unmount();

        session.serverId = 'pep';
        nav.pathname = '/game/pep/retinue/yuedan';
        render(<GameShell title="월단평"><p>본문</p></GameShell>);
        const tabs = screen.getByRole('navigation', { name: '하위 화면' });
        for (const a of tabs.querySelectorAll('a')) {
            expect(a.getAttribute('href'), a.textContent ?? '').toMatch(/^\/game\/pep(\/|$)/);
            expect(a.getAttribute('data-prefetch')).toBe('undefined');
        }
        // 부 묶음 — 지금 화면(월단평)이 켜지고, 「인물 일람」(P-R02) · 「포로 · 등용」(P-R05)은 새 화면으로
        expect(screen.getByRole('link', { name: '월단평' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', { name: '인물 일람' })).toHaveAttribute('href', '/game/pep/retinue/people');
        expect(screen.getByRole('link', { name: '포로 · 등용' })).toHaveAttribute('href', '/game/pep/retinue/captives');
        nav.pathname = '/game/retinue/yuedan';
    });
});
