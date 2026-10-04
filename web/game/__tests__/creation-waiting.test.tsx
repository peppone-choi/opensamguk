import * as matchers from '@testing-library/jest-dom/matchers';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CreatePage from '@/app/game/create/page';
import HistoricalPage from '@/app/game/create/historical/page';
import RegisterPage from '@/app/game/register/page';
expect.extend(matchers);
vi.mock('@/components/campaign/CampaignLink', () => ({ default: ({ children }: { children: React.ReactNode }) => <a href="/game/pep">{children}</a> }));
vi.mock('next/navigation', () => ({ redirect: vi.fn(), useRouter: () => ({ push: vi.fn() }), usePathname: () => '/game/pep/create/historical', useSearchParams: () => new URLSearchParams() }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep', refresh: vi.fn() }) }));
import { redirect } from 'next/navigation';
describe('E02/E03 delivered waiting destinations', () => {
  it('E02: has a real waiting page and no generation form', () => {
    render(<CreatePage />);
    expect(screen.getByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toBeVisible();
    expect(screen.getByRole('link', { name: '입구로' })).toHaveAttribute('href', '/game/pep');
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByRole('button', { name: '장수 생성' })).toBeNull();
  });
  it('E03: 서버 경로가 없으면(404) 같은 「생성 대기」 — 목록 · 시작 단추 없음', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })));
    render(<HistoricalPage />);
    expect(await screen.findByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toBeVisible();
    expect(screen.getByRole('link', { name: '입구로' })).toHaveAttribute('href', '/game/pep');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(screen.queryByRole('button', { name: '이 인물로 시작' })).toBeNull();
    vi.unstubAllGlobals();
  });
  it.each([['pep', '/game/pep'], ['../join', '/game'], ['', '/game']])('register %s returns to the entry, never enlistment', async (server, target) => {
    await RegisterPage({ searchParams: Promise.resolve({ server }) });
    expect(redirect).toHaveBeenLastCalledWith(target);
  });
});
