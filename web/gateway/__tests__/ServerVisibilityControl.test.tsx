import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import ServerVisibilityControl from '@/components/admin/ServerVisibilityControl';
afterEach(() => vi.unstubAllGlobals());
it('operator can close and reopen a server using the returned revision', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ state: 'PUBLIC', publiclyVisible: true, revision: '1' })))
        .mockResolvedValueOnce(new Response(JSON.stringify({ state: 'PUBLIC', publiclyVisible: false, revision: '2' })))
        .mockResolvedValueOnce(new Response(JSON.stringify({ state: 'PUBLIC', publiclyVisible: true, revision: '3' })));
    vi.stubGlobal('fetch', fetch);
    render(<ServerVisibilityControl serverId="pep" name="빼섭" />);
    fireEvent.click(await screen.findByRole('button', { name: '비공개로 전환' }));
    fireEvent.click(await screen.findByRole('button', { name: '공개로 전환' }));
    await screen.findByRole('button', { name: '비공개로 전환' });
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ publiclyVisible: false, expectedRevision: '1' });
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ publiclyVisible: true, expectedRevision: '2' });
});
it('conflicting change shows error and requires a fresh read', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ state: 'PUBLIC', publiclyVisible: true, revision: '1' })))
        .mockResolvedValueOnce(new Response('{}', { status: 409 })));
    render(<ServerVisibilityControl serverId="pep" name="빼섭" />);
    fireEvent.click(await screen.findByRole('button', { name: '비공개로 전환' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('다시 조회'));
    expect(screen.getByRole('button', { name: '공개 상태 다시 조회' })).toBeEnabled();
});

it('verification prevents changes and exposes the reason on touch', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ state: 'VERIFYING', publiclyVisible: true, revision: '1' })));
    vi.stubGlobal('fetch', fetch);
    render(<ServerVisibilityControl serverId="pep" name="빼섭" />);
    const button = await screen.findByRole('button', { name: '비공개로 전환' });
    expect(button).toHaveAttribute('aria-disabled');
    fireEvent.click(button);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('서버 검증이 끝난 뒤 공개 상태를 변경할 수 있습니다.')).toBeVisible();
});
