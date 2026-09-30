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
        isCampaignWorld: true, gameDate: '200년 3월 중순', refresh: () => {},
    }),
}));
vi.mock('../lib/campaign-reads', () => ({ useRenown: () => null }));

import CampaignLink from '../components/campaign/CampaignLink';
import GameShell from '../components/GameShell';

afterEach(() => { session.serverId = undefined; });

describe('CampaignLink', () => {
    it('does not prefetch the server-less address before the server is known', () => {
        render(<CampaignLink slug="yuedan">월단평</CampaignLink>);
        const link = screen.getByRole('link', { name: '월단평' });
        expect(link.getAttribute('href')).toBe('/game/yuedan');
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

describe('GameShell tabs (mobile preview requests)', () => {
    it('never prefetches a tab without the server, and every tab carries the server once known', () => {
        const { unmount } = render(<GameShell title="월단평" tab="장수 행동"><p>본문</p></GameShell>);
        const before = screen.getByRole('navigation', { name: '입력 여섯 가지' }).querySelectorAll('a');
        expect(before.length).toBeGreaterThan(0);
        for (const a of before) expect(a.getAttribute('data-prefetch'), a.textContent ?? '').toBe('false');
        unmount();

        session.serverId = 'pep';
        render(<GameShell title="월단평" tab="장수 행동"><p>본문</p></GameShell>);
        const links = [...screen.getByRole('navigation', { name: '입력 여섯 가지' }).querySelectorAll('a'),
            screen.getByRole('link', { name: '← 작전실' })];
        for (const a of links) {
            expect(a.getAttribute('href'), a.textContent ?? '').toMatch(/^\/game\/pep\//);
            expect(a.getAttribute('data-prefetch')).toBe('undefined');
        }
    });
});
