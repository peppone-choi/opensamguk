// 운영 콘솔 「게시물」(BoardControl) — 읽기 · 분류 · 고정 · 삭제 · 실패 문구를 지금 동작 그대로 묶는다.
// D105 1단계 5번(raw fetch → lib/admin-board + 훅) 이전 · 이후가 같은지 보는 시험이다.
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BoardControl from '@/components/admin/BoardControl';

type Post = { id: number; category: 'NOTICE' | 'FREE' | 'SUGGESTION'; authorName: string; title: string; contentHtml: string; pinned: boolean; deleted: boolean; createdAt: string; updatedAt: string };
const post = (id: number, extra: Partial<Post> = {}): Post => ({
    id, category: 'NOTICE', authorName: '운영자', title: `글 ${id}`, contentHtml: '<p>본문</p>', pinned: false, deleted: false,
    createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', ...extra,
});
const page = (content: Post[], p = 0, totalElements = content.length, totalPages = 1) => ({ content, page: p, size: 20, totalElements, totalPages });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const calls: { url: string; method: string; body?: string }[] = [];
let handler: (url: string, method: string, body?: string) => Response;

describe('운영 콘솔 · 게시물 (BoardControl)', () => {
    beforeEach(() => {
        calls.length = 0;
        handler = (url) => (url.startsWith('/api/board/posts?') ? json(page([post(1), post(2, { deleted: true })])) : json({}, 500));
        vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
            const url = String(input);
            const method = init?.method ?? 'GET';
            const body = typeof init?.body === 'string' ? init.body : undefined;
            calls.push({ url, method, body });
            return handler(url, method, body);
        });
    });

    it('공지 분류 첫 쪽을 삭제분까지 읽는다(no-store), 삭제된 글은 「삭제됨」', async () => {
        render(<BoardControl />);
        expect(await screen.findByText('글 1')).toBeInTheDocument();
        expect(calls[0].url).toBe('/api/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true');
        expect((globalThis.fetch as unknown as { mock: { calls: [unknown, RequestInit][] } }).mock.calls[0][1]).toEqual({ cache: 'no-store' });
        expect(screen.getByText('삭제됨')).toBeInTheDocument();
    });

    it('분류를 바꾸면 그 분류 첫 쪽을 읽는다', async () => {
        render(<BoardControl />);
        await screen.findByText('글 1');
        fireEvent.change(screen.getByLabelText('게시판 분류'), { target: { value: 'FREE' } });
        await waitFor(() => expect(calls.at(-1)?.url).toBe('/api/board/posts?category=FREE&page=0&size=20&includeDeleted=true'));
    });

    it('고정: PATCH 로 바꾸고 받은 글로 그 줄만 바꾼다 · 안내 문구', async () => {
        handler = (url, method) => {
            if (url === '/api/board/posts/1/pin' && method === 'PATCH') return json(post(1, { pinned: true }));
            return json(page([post(1), post(2, { deleted: true })]));
        };
        render(<BoardControl />);
        await screen.findByText('글 1');
        fireEvent.click(within(screen.getByRole('group', { name: '글 1 관리' })).getByRole('button', { name: '고정' }));
        expect(await screen.findByRole('status')).toHaveTextContent('게시물을 고정했습니다.');
        const patch = calls.find((c) => c.method === 'PATCH');
        expect(JSON.parse(patch!.body!)).toEqual({ pinned: true });
        expect(within(screen.getByRole('group', { name: '글 1 관리' })).getByRole('button', { name: '고정 해제' })).toBeInTheDocument();
        expect(calls.filter((c) => c.method === 'GET')).toHaveLength(1); // 다시 읽지 않는다
    });

    it('삭제: 확인을 거쳐 DELETE(204) 뒤 그 줄을 지운다 · 안내 문구', async () => {
        handler = (url, method) => {
            if (url === '/api/board/posts/1' && method === 'DELETE') return new Response(null, { status: 204 });
            return json(page([post(1), post(3)]));
        };
        render(<BoardControl />);
        await screen.findByText('글 1');
        fireEvent.click(within(screen.getByRole('group', { name: '글 1 관리' })).getByRole('button', { name: '삭제' }));
        expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
        fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '삭제' }));
        expect(await screen.findByRole('status')).toHaveTextContent('게시물을 삭제했습니다.');
        expect(screen.queryByText('글 1')).toBeNull();
        expect(screen.getByText('글 3')).toBeInTheDocument();
    });

    it('삭제: 둘째 쪽의 마지막 글을 지우면 앞 쪽을 다시 읽는다', async () => {
        handler = (url, method) => {
            if (url === '/api/board/posts/21' && method === 'DELETE') return new Response(null, { status: 204 });
            if (url.includes('page=1')) return json(page([post(21)], 1, 21, 2));
            return json(page(Array.from({ length: 20 }, (_, i) => post(i + 1)), 0, 21, 2));
        };
        render(<BoardControl />);
        await screen.findByText('글 1');
        fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
        await screen.findByText('글 21');
        fireEvent.click(within(screen.getByRole('group', { name: '글 21 관리' })).getByRole('button', { name: '삭제' }));
        fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '삭제' }));
        await waitFor(() => expect(calls.at(-1)?.url).toBe('/api/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true'));
    });

    it('읽기 실패: 서버 문구를 그대로 · 형식이 다르면 「형식이 올바르지 않습니다」 · 연결 실패는 기본 문구', async () => {
        handler = () => json({ message: '권한이 없습니다.' }, 403);
        const { unmount } = render(<BoardControl />);
        expect(await screen.findByRole('alert')).toHaveTextContent('권한이 없습니다.');
        unmount();
        handler = () => json({ content: 'nope' });
        const second = render(<BoardControl />);
        expect(await screen.findByRole('alert')).toHaveTextContent('게시물 목록 형식이 올바르지 않습니다.');
        second.unmount();
        vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('network'));
        render(<BoardControl />);
        expect(await screen.findByRole('alert')).toHaveTextContent('게시물 목록을 불러오지 못했습니다.');
    });

    it('고정 · 삭제 실패: 서버 문구, 없으면 기본 문구', async () => {
        handler = (url, method) => {
            if (method === 'PATCH') return json({ message: '잠긴 글입니다.' }, 409);
            if (method === 'DELETE') return json({}, 500);
            return json(page([post(1)]));
        };
        render(<BoardControl />);
        await screen.findByText('글 1');
        fireEvent.click(screen.getByRole('button', { name: '고정' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('잠긴 글입니다.');
        fireEvent.click(screen.getByRole('button', { name: '삭제' }));
        fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '삭제' }));
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('게시물을 삭제하지 못했습니다.'));
        expect(screen.getByText('글 1')).toBeInTheDocument();
    });
});
