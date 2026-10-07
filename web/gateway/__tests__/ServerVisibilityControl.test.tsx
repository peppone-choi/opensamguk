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
