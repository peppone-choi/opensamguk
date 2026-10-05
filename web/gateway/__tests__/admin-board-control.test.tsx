import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BoardControl from '@/components/admin/BoardControl';

type BoardPost = {
    id: number;
    category: 'NOTICE' | 'FREE' | 'SUGGESTION';
    authorName: string;
    title: string;
    contentHtml: string;
    pinned: boolean;
    deleted: boolean;
    createdAt: string;
    updatedAt: string;
};

const post: BoardPost = {
    id: 7,
    category: 'NOTICE',
    authorName: '운영자',
    title: '서버 점검 안내',
    contentHtml: '점검 내용',
    pinned: false,
    deleted: false,
    createdAt: '2026-08-11T10:00:00Z',
    updatedAt: '2026-08-11T10:00:00Z',
};

function response(status: number, body?: unknown): Response {
    return new Response(body === undefined ? null : JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

function page(posts: readonly BoardPost[], currentPage = 0, totalElements = posts.length, totalPages = 1) {
    return {
        content: posts,
        page: currentPage,
        size: 20,
        totalElements,
        totalPages,
    };
}

function deferred<T>() {
    let resolve: (value: T) => void = () => undefined;
    const promise = new Promise<T>((next) => {
        resolve = next;
    });
    return { promise, resolve };
}

describe('admin board control', () => {
    let pinned = false;
    let deleted = false;

    beforeEach(() => {
        pinned = false;
        deleted = false;
        vi.stubGlobal('React', React);
        vi.stubGlobal(
            'fetch',
            vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
                const path = String(input);
                if (path.startsWith('/api/board/posts?')) {
                    return Promise.resolve(response(200, page(deleted ? [] : [{ ...post, pinned }])));
                }
                if (path === '/api/board/posts/7/pin') {
                    pinned = init?.body === JSON.stringify({ pinned: true });
                    return Promise.resolve(response(200, { ...post, pinned }));
                }
                if (path === '/api/board/posts/7') {
                    deleted = true;
                    return Promise.resolve(new Response(null, { status: 204 }));
                }
                return Promise.resolve(response(404, { message: '게시물을 찾을 수 없습니다.', status: 404 }));
            }),
        );
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('loads NOTICE posts and sends the locked pin request', async () => {
        render(<BoardControl />);

        expect(await screen.findByText('서버 점검 안내')).toBeInTheDocument();
        expect(fetch).toHaveBeenCalledWith(
            '/api/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true',
            { cache: 'no-store' },
        );

        fireEvent.click(screen.getByRole('button', { name: '고정' }));

        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/board/posts/7/pin',
                expect.objectContaining({
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ pinned: true }),
                }),
            ),
        );
        expect(await screen.findByRole('button', { name: '고정 해제' })).toBeInTheDocument();
    });

    it('reloads the FREE and SUGGESTION category queries when the category changes', async () => {
        render(<BoardControl />);

        await screen.findByText('서버 점검 안내');
        fireEvent.change(screen.getByLabelText('게시판 분류'), { target: { value: 'FREE' } });

        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/board/posts?category=FREE&page=0&size=20&includeDeleted=true',
                { cache: 'no-store' },
            ),
        );

        fireEvent.change(screen.getByLabelText('게시판 분류'), { target: { value: 'SUGGESTION' } });

        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/board/posts?category=SUGGESTION&page=0&size=20&includeDeleted=true',
                { cache: 'no-store' },
            ),
        );
    });

    it('does not render server contentHtml in the administrative list', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn((input: RequestInfo | URL) => {
                const path = String(input);
                if (path.startsWith('/api/board/posts?')) {
                    return Promise.resolve(response(200, page([{
                        ...post,
                        contentHtml: '<img alt="unsafe board content" src="/unexpected.png"><span>숨겨진 본문</span>',
                    }])));
                }
                return Promise.resolve(response(404, { message: '게시물을 찾을 수 없습니다.', status: 404 }));
            }),
        );

        render(<BoardControl />);

        expect(await screen.findByText('서버 점검 안내')).toBeInTheDocument();
        expect(screen.queryByRole('img', { name: 'unsafe board content' })).toBeNull();
        expect(screen.queryByText('숨겨진 본문')).toBeNull();
    });

    it('confirms before issuing the locked 204 delete request', async () => {
        render(<BoardControl />);

        await screen.findByText('서버 점검 안내');
        fireEvent.click(screen.getByRole('button', { name: '삭제' }));
        const dialog = await screen.findByRole('dialog', { name: '게시물 삭제' });
        fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }));

        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith('/api/board/posts/7', { method: 'DELETE' }),
        );
        expect(await screen.findByRole('status')).toHaveTextContent('게시물을 삭제했습니다.');
    });

    it('reloads the previous page when delete empties the final page', async () => {
        let finalPostDeleted = false;
        let firstPageRequests = 0;
        const finalPost = { ...post, id: 8, title: '마지막 게시물' };
        vi.stubGlobal(
            'fetch',
            vi.fn((input: RequestInfo | URL) => {
                const path = String(input);
                if (path === '/api/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true') {
                    firstPageRequests += 1;
                    return Promise.resolve(response(200, page([{ ...post }], 0, finalPostDeleted ? 20 : 21, finalPostDeleted ? 1 : 2)));
                }
                if (path === '/api/board/posts?category=NOTICE&page=1&size=20&includeDeleted=true') {
                    return Promise.resolve(response(200, page([finalPost], 1, 21, 2)));
                }
                if (path === '/api/board/posts/8') {
                    finalPostDeleted = true;
                    return Promise.resolve(new Response(null, { status: 204 }));
                }
                return Promise.resolve(response(404, { message: '게시물을 찾을 수 없습니다.', status: 404 }));
            }),
        );

        render(<BoardControl />);
        await screen.findByText('서버 점검 안내');
        fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
        expect(await screen.findByText('마지막 게시물')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: '삭제' }));
        fireEvent.click(within(await screen.findByRole('dialog', { name: '게시물 삭제' })).getByRole('button', { name: '삭제' }));

        await waitFor(() => expect(firstPageRequests).toBe(2));
        expect(await screen.findByText('서버 점검 안내')).toBeInTheDocument();
    });

    it('keeps a newer category response when an earlier list request resolves late', async () => {
        const notice = deferred<Response>();
        const free = deferred<Response>();
        vi.stubGlobal(
            'fetch',
            vi.fn((input: RequestInfo | URL) => {
                const path = String(input);
                if (path === '/api/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true') return notice.promise;
                if (path === '/api/board/posts?category=FREE&page=0&size=20&includeDeleted=true') return free.promise;
                return Promise.resolve(response(404, { message: '게시물을 찾을 수 없습니다.', status: 404 }));
            }),
        );

        render(<BoardControl />);
        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true',
                { cache: 'no-store' },
            ),
        );
        fireEvent.change(screen.getByLabelText('게시판 분류'), { target: { value: 'FREE' } });
        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/board/posts?category=FREE&page=0&size=20&includeDeleted=true',
                { cache: 'no-store' },
            ),
        );

        await act(async () => {
            free.resolve(response(200, page([{ ...post, category: 'FREE', title: '자유 게시물' }])));
            await free.promise;
        });
        expect(screen.getByText('자유 게시물')).toBeInTheDocument();
        await act(async () => {
            notice.resolve(response(200, page([{ ...post, title: '늦은 공지' }])));
            await notice.promise;
        });

        expect(screen.queryByText('늦은 공지')).toBeNull();
        expect(screen.getByText('자유 게시물')).toBeInTheDocument();
    });
});

// D105 1단계 5번(raw fetch → lib/admin-board + 훅) 리팩터 전에 덧붙인 특성 시험 — 위 시험(#378 · #449 · #504)은 그대로 둔다.
describe('운영 콘솔 · 게시물 (BoardControl) — D105 1단계 5번 리팩터 특성 시험(덧붙임)', () => {
    type AdminPost = { id: number; category: 'NOTICE' | 'FREE' | 'SUGGESTION'; authorName: string; title: string; contentHtml: string; pinned: boolean; deleted: boolean; createdAt: string; updatedAt: string };
    const mkPost = (id: number, extra: Partial<AdminPost> = {}): AdminPost => ({
        id, category: 'NOTICE', authorName: '운영자', title: `글 ${id}`, contentHtml: '<p>본문</p>', pinned: false, deleted: false,
        createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', ...extra,
    });
    const mkPage = (content: AdminPost[], p = 0, totalElements = content.length, totalPages = 1) => ({ content, page: p, size: 20, totalElements, totalPages });
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
    const calls: { url: string; method: string; body?: string }[] = [];
    let handler: (url: string, method: string, body?: string) => Response;

    afterEach(() => {
        vi.restoreAllMocks();
    });

    beforeEach(() => {
        calls.length = 0;
        handler = (url) => (url.startsWith('/api/board/posts?') ? json(mkPage([mkPost(1), mkPost(2, { deleted: true })])) : json({}, 500));
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
            if (url === '/api/board/posts/1/pin' && method === 'PATCH') return json(mkPost(1, { pinned: true }));
            return json(mkPage([mkPost(1), mkPost(2, { deleted: true })]));
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
            return json(mkPage([mkPost(1), mkPost(3)]));
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
            if (url.includes('page=1')) return json(mkPage([mkPost(21)], 1, 21, 2));
            return json(mkPage(Array.from({ length: 20 }, (_, i) => mkPost(i + 1)), 0, 21, 2));
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
            return json(mkPage([mkPost(1)]));
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
