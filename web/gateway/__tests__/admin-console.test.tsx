import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...rest }: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a href={href} {...rest}>{children}</a> }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }) }));
vi.mock('@/lib/auth-context', () => {
    const session = () => ({ user: { id: 1, username: 'admin', email: null, nickname: '운영자', role: 'ADMIN', picture: null, imageServer: 0 }, loading: false, refresh: vi.fn(), logout: vi.fn() });
    return { useAuth: session, useAuthOptional: session, AuthProvider: ({ children }: { children: React.ReactNode }) => children };
});
vi.mock('@/components/admin/MemberControl', () => ({ default: () => <div>members</div> }));
vi.mock('@/components/admin/BoardControl', () => ({ default: () => <div>board</div> }));
vi.mock('@/components/admin/BoardReportControl', () => ({ default: () => <div>reports</div> }));
vi.mock('@/components/admin/NoticeControl', () => ({ default: () => <div>notices</div> }));

import AdminPage from '@/app/admin/page';

const svc = (tag: string) => ({ reachable: true, version: '0.9.2', imageTag: tag, buildTime: null });
const VERSION = {
    gateway: svc('sha-3aa678b'),
    skew: false,
    servers: [
        { id: 'pep', name: 'pep', generation: 1, scenarioCode: 'scenario_1020', gameApi: svc('sha-3aa678b'), gameEngine: svc('sha-3aa678b'), skew: false },
        { id: 'uni', name: '통일 서버', generation: 3, scenarioCode: 'scenario_9', gameApi: svc('sha-3aa678b'), gameEngine: svc('sha-19c2e0d'), skew: true },
    ],
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let paused: Record<string, boolean>;

function fetchFake() {
    return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (path === '/api/proxy/admin/version') return json(VERSION);
        if (path === '/api/proxy/admin/scenarios') return json({ scenarios: [{ code: 'scenario_1020', title: '군웅할거' }] });
        if (path.startsWith('/api/proxy/admin/deploy/status?serverId=pep')) return json({ configured: true, serverId: 'pep', currentTag: 'sha-3aa678b', availableTags: [], latestTag: 'sha-4f01c77', promotionAvailable: true });
        if (path.startsWith('/api/proxy/admin/deploy/status')) return json({ configured: true, serverId: 'uni', currentTag: 'sha-3aa678b', availableTags: [] });
        const id = new URL(path, 'http://x').searchParams.get('serverId') ?? '';
        if (path.startsWith('/api/proxy/admin/turn-daemon/status')) {
            return json({ paused: paused[id], running: !paused[id], statusLabel: '', catchUp: id === 'pep' ? { active: true, multiplier: 2, backlogSeconds: 3600, remainingSeconds: 3600, etaAt: null, initialBacklogSeconds: 3600, recoveredSeconds: 0 } : null });
        }
        if (path.startsWith('/api/proxy/admin/turn-daemon/pause') && init?.method === 'POST') { paused[id] = true; return json({ paused: true, changed: true, statusLabel: '' }); }
        if (path.startsWith('/api/proxy/admin/turn-daemon/resume') && init?.method === 'POST') { paused[id] = false; return json({ paused: false, changed: true, statusLabel: '' }); }
        return json({}, 404);
    });
}

describe('P-G09 운영 콘솔', () => {
    beforeEach(() => {
        paused = { pep: false, uni: true };
        vi.stubGlobal('fetch', fetchFake());
    });
    afterEach(() => vi.unstubAllGlobals());

    it('탭 8개(위험 등급 칩) · 기본은 개요', async () => {
        render(<AdminPage />);
        const rail = screen.getByRole('navigation', { name: '운영 콘솔' });
        expect(within(rail).getAllByRole('button').map((b) => b.textContent)).toEqual([
            '개요조회', '회원가역 · 파괴적', '게시판가역', '신고가역', '공지가역', '턴가역', '따라잡기가역', '서버배포 · 파괴적',
        ]);
        expect(within(rail).getByRole('button', { name: '개요' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('heading', { level: 1, name: '개요' })).toBeInTheDocument();
        expect(screen.getByText('위험 등급 · 조회')).toBeInTheDocument();
        fireEvent.click(within(rail).getByRole('button', { name: '서버' }));
        expect(screen.getByText('위험 등급 · 배포 · 파괴적')).toHaveClass('os-chip--rust');
        fireEvent.click(within(rail).getByRole('button', { name: '신고' }));
        expect(screen.getByText('reports')).toBeInTheDocument();
    });

    it('개요: 시나리오 제목 · 새 버전 · 턴 칩(쉬운 말) · 서버별 게임 관리 링크', async () => {
        render(<AdminPage />);
        const table = await screen.findByRole('table');
        const rows = within(table).getAllByRole('row').slice(1);
        expect(rows).toHaveLength(2);
        expect(within(rows[0]).getByText('군웅할거')).toBeInTheDocument();
        expect(within(rows[0]).getByText('scenario_1020')).toBeInTheDocument();
        await waitFor(() => expect(within(rows[0]).getByText('따라잡는 중 · 2배속')).toBeInTheDocument());
        expect(within(rows[0]).getByText('새 버전 sha-4f01c77')).toBeInTheDocument();
        expect(within(rows[1]).getByText('턴 멈춤')).toBeInTheDocument();
        expect(within(rows[1]).getByText('불일치')).toBeInTheDocument();
        expect(within(rows[0]).getByRole('link', { name: '게임 관리' }).getAttribute('href')).toMatch(/pep.*admin$/);
        expect(screen.queryByText(/동결중|가동중|데몬/)).toBeNull();
    });

    it('턴: 멈추기는 확인을 거치고, 이미 멈춘 서버는 사유와 함께 잠긴다', async () => {
        render(<AdminPage />);
        await screen.findByRole('table');
        fireEvent.click(within(screen.getByRole('navigation', { name: '운영 콘솔' })).getByRole('button', { name: '턴' }));
        expect(await screen.findByText('턴 도는 중')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '다시 돌리기' })).toHaveAttribute('data-reason', '이미 돌고 있습니다');
        fireEvent.click(screen.getByRole('button', { name: '턴 멈추기' }));
        const dialog = await screen.findByRole('dialog', { name: '턴 멈추기' });
        expect(dialog).toHaveTextContent('pep 1기 서버의 턴이 멈춥니다.');
        expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining('turn-daemon/pause'), expect.anything());
        fireEvent.click(within(dialog).getByRole('button', { name: '턴 멈추기' }));
        expect(await screen.findByText('턴 멈춤')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '턴 멈추기' })).toHaveAttribute('data-reason', '이미 멈춰 있습니다');
        fireEvent.click(screen.getByRole('radio', { name: '통일 서버 3기' }));
        expect(await screen.findByText('턴 멈춤')).toBeInTheDocument();
    });

    it('따라잡기: 따라잡는 중이 아니면 「정상 속도로 돌고 있습니다」', async () => {
        render(<AdminPage />);
        await screen.findByRole('table');
        fireEvent.click(within(screen.getByRole('navigation', { name: '운영 콘솔' })).getByRole('button', { name: '따라잡기' }));
        expect(await screen.findByText(/현재 2배속/)).toBeInTheDocument();
        fireEvent.click(screen.getByRole('radio', { name: '통일 서버 3기' }));
        expect(await screen.findByText('정상 속도로 돌고 있습니다.')).toBeInTheDocument();
    });
});
