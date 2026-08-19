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
                if (path.startsWith('/api/proxy/board/posts?')) {
                    return Promise.resolve(response(200, page(deleted ? [] : [{ ...post, pinned }])));
                }
                if (path === '/api/proxy/board/posts/7/pin') {
                    pinned = init?.body === JSON.stringify({ pinned: true });
                    return Promise.resolve(response(200, { ...post, pinned }));
                }
                if (path === '/api/proxy/board/posts/7') {
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
            '/api/proxy/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true',
            { cache: 'no-store' },
        );

        fireEvent.click(screen.getByRole('button', { name: '고정' }));

        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/proxy/board/posts/7/pin',
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
                '/api/proxy/board/posts?category=FREE&page=0&size=20&includeDeleted=true',
                { cache: 'no-store' },
            ),
        );

        fireEvent.change(screen.getByLabelText('게시판 분류'), { target: { value: 'SUGGESTION' } });

        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/proxy/board/posts?category=SUGGESTION&page=0&size=20&includeDeleted=true',
                { cache: 'no-store' },
            ),
        );
    });

    it('does not render server contentHtml in the administrative list', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn((input: RequestInfo | URL) => {
                const path = String(input);
                if (path.startsWith('/api/proxy/board/posts?')) {
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
            expect(fetch).toHaveBeenCalledWith('/api/proxy/board/posts/7', { method: 'DELETE' }),
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
                if (path === '/api/proxy/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true') {
                    firstPageRequests += 1;
                    return Promise.resolve(response(200, page([{ ...post }], 0, finalPostDeleted ? 20 : 21, finalPostDeleted ? 1 : 2)));
                }
                if (path === '/api/proxy/board/posts?category=NOTICE&page=1&size=20&includeDeleted=true') {
                    return Promise.resolve(response(200, page([finalPost], 1, 21, 2)));
                }
                if (path === '/api/proxy/board/posts/8') {
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
                if (path === '/api/proxy/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true') return notice.promise;
                if (path === '/api/proxy/board/posts?category=FREE&page=0&size=20&includeDeleted=true') return free.promise;
                return Promise.resolve(response(404, { message: '게시물을 찾을 수 없습니다.', status: 404 }));
            }),
        );

        render(<BoardControl />);
        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/proxy/board/posts?category=NOTICE&page=0&size=20&includeDeleted=true',
                { cache: 'no-store' },
            ),
        );
        fireEvent.change(screen.getByLabelText('게시판 분류'), { target: { value: 'FREE' } });
        await waitFor(() =>
            expect(fetch).toHaveBeenCalledWith(
                '/api/proxy/board/posts?category=FREE&page=0&size=20&includeDeleted=true',
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
