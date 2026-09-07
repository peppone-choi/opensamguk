import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import NoticeControl from '@/components/admin/NoticeControl';

const calls: { url: string; method: string; body?: string }[] = [];
const BASE = [
    { id: 1, title: '첫 공지', body: '본문', pinned: false, publishedAt: '2026-09-05T00:00:00Z', deleted: false },
    { id: 2, title: '지운 공지', body: '본문', pinned: false, publishedAt: '2026-09-04T00:00:00Z', deleted: true },
];
let notices = [...BASE];

describe('운영 콘솔 · 공지 (NoticeControl)', () => {
    beforeEach(() => {
        calls.length = 0;
        notices = BASE.map((n) => ({ ...n }));
        vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
            const url = String(input);
            const method = init?.method ?? 'GET';
            calls.push({ url, method, body: typeof init?.body === 'string' ? init.body : undefined });
            const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
            if (url === '/api/proxy/admin/notices' && method === 'GET') return json({ notices });
            if (url === '/api/proxy/admin/notices' && method === 'POST') {
                const parsed = JSON.parse(init?.body as string);
                const created = { id: 3, title: parsed.title, body: parsed.body, pinned: parsed.pinned, publishedAt: '2026-09-06T00:00:00Z', deleted: false };
                notices = [created, ...notices];
                return json(created);
            }
            if (url === '/api/proxy/admin/notices/1/pin' && method === 'PATCH') { notices = notices.map((n) => (n.id === 1 ? { ...n, pinned: true } : n)); return json(notices[0]); }
            if (url === '/api/proxy/admin/notices/1' && method === 'DELETE') { notices = notices.map((n) => (n.id === 1 ? { ...n, deleted: true } : n)); return json(notices[0]); }
            throw new Error(`unexpected request: ${method} ${url}`);
        });
    });

    it('lists notices including soft-deleted rows and creates a new one through the admin proxy', async () => {
        render(<NoticeControl />);
        expect(await screen.findByText('첫 공지')).toBeInTheDocument();
        expect(screen.getByText('삭제됨')).toBeInTheDocument();
        fireEvent.change(screen.getByLabelText('공지 제목'), { target: { value: '새 공지' } });
        fireEvent.change(screen.getByLabelText('공지 내용'), { target: { value: '내용입니다' } });
        fireEvent.click(screen.getByRole('button', { name: '공지 등록' }));
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('공지를 등록했습니다.'));
        expect(calls.some((c) => c.method === 'POST' && c.body?.includes('새 공지'))).toBe(true);
        expect(await screen.findByText('새 공지')).toBeInTheDocument();
    });

    it('pins a notice and only soft-deletes after an explicit confirmation', async () => {
        render(<NoticeControl />);
        await screen.findByText('첫 공지');
        fireEvent.click(screen.getByRole('button', { name: '고정' }));
        await waitFor(() => expect(calls.some((c) => c.url.endsWith('/1/pin') && c.method === 'PATCH')).toBe(true));
        fireEvent.click(screen.getByRole('button', { name: '삭제' }));
        expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
        const dialog = screen.getByRole('dialog', { name: '공지 삭제 확인' });
        fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }));
        await waitFor(() => expect(calls.some((c) => c.url.endsWith('/1') && c.method === 'DELETE')).toBe(true));
    });

    it('surfaces the server message when the feed cannot be loaded', async () => {
        vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ message: '인증이 필요합니다.' }), { status: 401 }));
        render(<NoticeControl />);
        expect(await screen.findByRole('alert')).toHaveTextContent('인증이 필요합니다.');
    });
});
