import { render, screen } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import GameShell from '../components/GameShell';

vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/retinue', useSearchParams: () => new URLSearchParams() }));
vi.mock('next/link', () => ({ default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => <a href={href} {...rest}>{children}</a> }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep', generalId: 7, loading: false, error: null, frontInfo: null }) }));

test('bare — 제목 줄(머리 · 하위 탭) 없이 제목은 화면 읽기용 h2 하나(작전실, 보드 V31K4WarRoom · MWarRoom)', () => {
    const { container } = render(<GameShell title="작전실" bare requiresHwiha={false}><p>본문</p></GameShell>);
    const heading = screen.getByRole('heading', { level: 2, name: '작전실' });
    expect(heading).toHaveClass('sr-only');
    expect(screen.getAllByRole('heading', { level: 2 })).toHaveLength(1);
    expect(screen.queryByRole('navigation', { name: '하위 화면' })).toBeNull();
    expect(container).toHaveTextContent('본문');
});

test('bare 가 아니면 제목 줄 그대로(다른 화면 불변) — 보이는 h2 + 하위 탭', () => {
    render(<GameShell title="부" requiresHwiha={false}><p>본문</p></GameShell>);
    const heading = screen.getByRole('heading', { level: 2, name: '부' });
    expect(heading).not.toHaveClass('sr-only');
    expect(screen.getByRole('navigation', { name: '하위 화면' })).toBeInTheDocument();
});
