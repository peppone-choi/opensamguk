import * as matchers from '@testing-library/jest-dom/matchers';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CreatePage from '@/app/game/create/page';
import HistoricalPage from '@/app/game/create/historical/page';
import RegisterPage from '@/app/game/register/page';
expect.extend(matchers);
vi.mock('@/components/campaign/CampaignLink', () => ({ default: ({ children }: { children: React.ReactNode }) => <a href="/game/pep">{children}</a> }));
vi.mock('next/navigation', () => ({ redirect: vi.fn() }));
import { redirect } from 'next/navigation';
describe('E02/E03 delivered waiting destinations', () => {
  it.each([CreatePage, HistoricalPage])('has a real waiting page and no generation form', Page => {
    render(<Page />);
    expect(screen.getByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toBeVisible();
    expect(screen.getByRole('link', { name: '입구로' })).toHaveAttribute('href', '/game/pep');
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByRole('button', { name: '장수 생성' })).toBeNull();
  });
  it.each([['pep', '/game/pep'], ['../join', '/game'], ['', '/game']])('register %s returns to the entry, never enlistment', async (server, target) => {
    await RegisterPage({ searchParams: Promise.resolve({ server }) });
    expect(redirect).toHaveBeenLastCalledWith(target);
  });
});
