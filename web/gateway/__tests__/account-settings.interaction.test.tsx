import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const replace = vi.fn();
const refresh = vi.fn().mockResolvedValue(null);
const logout = vi.fn().mockResolvedValue(undefined);

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace }) }));
vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('@/lib/auth-context', () => ({
    useAuth: () => ({
        user: { id: 1, username: 'tester', email: null, nickname: null, role: 'USER', picture: 'old.png', imageServer: 1 },
        refresh,
        logout,
    }),
}));

import AccountPage from '@/app/account/page';
import { IMAGE_CDN_BASE } from '@/lib/constants';

function response(status = 200, body = '{}'): Response {
    return new Response(body, { status, headers: { 'Content-Type': 'application/json' } });
}

// jsdom의 file input에 파일을 얹는다 (직접 .files 대입은 막혀 defineProperty로 우회).
function selectFile(file: File): void {
    const input = screen.getByLabelText('전콘 이미지 파일') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    fireEvent.change(input);
}

function iconFile(bytes: number, name = 'icon.png', type = 'image/png'): File {
    return new File([new Uint8Array(bytes)], name, { type });
}

// createImageBitmap은 jsdom에 없어 스텁 — 정규화가 읽는 width/height만 제공.
function stubBitmap(width: number, height: number): void {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue({ width, height, close: vi.fn() }));
}

// jsdom엔 canvas 인코더가 없다 — 규격 밖 이미지가 실제로 재인코딩돼 전송되는지만 본다.
function stubCanvas(blob: Blob | null): void {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn(), fillRect: vi.fn() } as never);
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => callback(blob));
}

// 피드백은 그 액션을 일으킨 컨트롤과 같은 컨테이너 안에서만 떠야 한다(화면 밖 전역 배너 금지).
function panel(heading: string): HTMLElement {
    return screen.getByRole('heading', { name: heading }).closest('section') as HTMLElement;
}

function uploadedFile(): File {
    const init = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls[0][1];
    return (init.body as FormData).get('file') as File;
}

describe('account settings interactions', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response()));
        vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
        stubBitmap(96, 96);
        stubCanvas(new Blob([new Uint8Array(8_000)], { type: 'image/jpeg' }));
    });

    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it('submits a password change and reports it inside the password form', async () => {
        render(<AccountPage />);
        fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: 'oldpass' } });
        fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value: 'newpass1' } });
        fireEvent.click(screen.getByRole('button', { name: '변경' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/password', expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ currentPassword: 'oldpass', newPassword: 'newpass1' }),
        })));
        const form = screen.getByLabelText('현재 비밀번호').closest('form') as HTMLElement;
        await waitFor(() => expect(within(form).getByRole('status')).toHaveTextContent('비밀번호를 변경했습니다.'));
        // 전콘 패널로 새지 않는다.
        expect(within(panel('전콘')).queryByRole('status')).toBeNull();
    });

    it('uploads a spec-compliant icon byte-for-byte and drives the preview from the server canonical response', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, JSON.stringify({
            id: 1, username: 'tester', email: null, nickname: null, role: 'USER', picture: 'a1b2c3d4.png', imageServer: 1,
        }))));
        const original = iconFile(2048);
        render(<AccountPage />);
        selectFile(original);
        fireEvent.click(screen.getByRole('button', { name: '업로드' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/profile-icon', expect.objectContaining({ method: 'POST' })));
        const init = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls[0][1];
        expect(init.body).toBeInstanceOf(FormData);
        // 규격 내 파일은 변환 없이 원본 그대로 나간다.
        expect(uploadedFile()).toBe(original);
        expect(HTMLCanvasElement.prototype.toBlob).not.toHaveBeenCalled();
        // 헤더에 Authorization/토큰을 프론트에서 붙이지 않는다 — 프록시가 서버측 쿠키로만 처리.
        expect(init.headers).toBeUndefined();
        expect(await within(panel('전콘')).findByRole('status')).toHaveTextContent('전콘을 업로드했습니다.');
        expect(screen.getByRole('img', { name: '현재 전콘' })).toHaveAttribute('src', '/d_pic/a1b2c3d4.png');
    });

    it.each([
        ['오버사이즈', iconFile(51_201), 96, 96],
        ['64px 미만', iconFile(2048), 32, 32],
        ['128px 초과', iconFile(2048), 4000, 3000],
        ['비정사각형', iconFile(2048), 64, 128],
    ] as const)('converts %s instead of silently refusing it', async (_label, file, w, h) => {
        stubBitmap(w, h);
        render(<AccountPage />);
        selectFile(file);
        fireEvent.click(screen.getByRole('button', { name: '업로드' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/profile-icon', expect.objectContaining({ method: 'POST' })));
        const sent = uploadedFile();
        expect(sent).not.toBe(file);
        expect(sent.type).toBe('image/jpeg');
        expect(sent.size).toBeLessThanOrEqual(51_200);
        expect(await within(panel('전콘')).findByRole('status')).toHaveTextContent('전콘을 업로드했습니다.');
    });

    it('explains it in the 전콘 panel when the image cannot be shrunk under 50KB', async () => {
        stubBitmap(4000, 3000);
        stubCanvas(new Blob([new Uint8Array(60_000)], { type: 'image/webp' }));
        render(<AccountPage />);
        selectFile(iconFile(3_000_000));
        fireEvent.click(screen.getByRole('button', { name: '업로드' }));

        expect(await within(panel('전콘')).findByRole('alert'))
            .toHaveTextContent('이미지를 50KB 이하로 줄이지 못했습니다.');
        expect(fetch).not.toHaveBeenCalled();
    });

    it('explains it in the 전콘 panel when the browser cannot convert at all', async () => {
        vi.stubGlobal('createImageBitmap', undefined);
        render(<AccountPage />);
        selectFile(iconFile(3_000_000));
        fireEvent.click(screen.getByRole('button', { name: '업로드' }));

        expect(await within(panel('전콘')).findByRole('alert'))
            .toHaveTextContent('이 브라우저는 이미지 자동 변환을 지원하지 않습니다.');
        expect(fetch).not.toHaveBeenCalled();
    });

    it('asks for a file in the 전콘 panel when none is selected', async () => {
        render(<AccountPage />);
        fireEvent.click(screen.getByRole('button', { name: '업로드' }));

        expect(await within(panel('전콘')).findByRole('alert')).toHaveTextContent('업로드할 이미지를 선택하세요.');
        expect(fetch).not.toHaveBeenCalled();
    });

    it('shows the 하루 1회 message on a 409 and keeps the existing preview', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(409, JSON.stringify({
            error: '프로필 아이콘은 하루에 한 번만 변경할 수 있습니다.',
        }))));
        render(<AccountPage />);
        const before = screen.getByRole('img', { name: '현재 전콘' }).getAttribute('src');
        selectFile(iconFile(2048));
        fireEvent.click(screen.getByRole('button', { name: '업로드' }));

        expect(await within(panel('전콘')).findByRole('alert')).toHaveTextContent('프로필 아이콘은 하루에 한 번만 변경할 수 있습니다.');
        expect(screen.queryByRole('status')).toBeNull();
        expect(screen.getByRole('img', { name: '현재 전콘' })).toHaveAttribute('src', before!);
    });

    it('never reports a server reject as success even when the client converted the image', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(400, JSON.stringify({
            error: '올바른 프로필 아이콘 이미지가 아닙니다.',
        }))));
        stubBitmap(4000, 3000);
        render(<AccountPage />);
        selectFile(iconFile(3_000_000));
        fireEvent.click(screen.getByRole('button', { name: '업로드' }));

        expect(await within(panel('전콘')).findByRole('alert')).toHaveTextContent('올바른 프로필 아이콘 이미지가 아닙니다.');
        expect(screen.queryByRole('status')).toBeNull();
    });

    it('surfaces the 401 boundary message without leaking a token', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(401, JSON.stringify({ error: '로그인이 필요합니다.' }))));
        render(<AccountPage />);
        selectFile(iconFile(2048));
        fireEvent.click(screen.getByRole('button', { name: '업로드' }));

        const alert = await within(panel('전콘')).findByRole('alert');
        expect(alert).toHaveTextContent('로그인이 필요합니다.');
        expect(alert.textContent).not.toMatch(/Bearer|eyJ/);
        expect(screen.queryByRole('status')).toBeNull();
    });

    it('deletes the uploaded icon through the DELETE endpoint and converges to the default', async () => {
        render(<AccountPage />);
        fireEvent.click(screen.getByRole('button', { name: '삭제' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/profile-icon', expect.objectContaining({
            method: 'DELETE',
        })));
        expect(await within(panel('전콘')).findByRole('status')).toHaveTextContent('전콘을 삭제했습니다.');
        expect(screen.getByRole('img', { name: '현재 전콘' })).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/default.jpg`);
    });

    it('reports a shared-icon save next to that form, not next to the upload button', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, JSON.stringify({
            id: 1, username: 'tester', email: null, nickname: null, role: 'USER', picture: '1001', imageServer: 0,
        }))));
        render(<AccountPage />);
        fireEvent.click(screen.getByRole('button', { name: '저장' }));

        const sharedForm = screen.getByLabelText('전콘 파일명').closest('form') as HTMLElement;
        await waitFor(() => expect(within(sharedForm).getByRole('status')).toHaveTextContent('전콘을 저장했습니다.'));
        const uploadForm = screen.getByLabelText('전콘 이미지 파일').closest('form') as HTMLElement;
        expect(within(uploadForm).queryByRole('status')).toBeNull();
    });

    it('resolves account portrait states through the shared icons contract', () => {
        render(<AccountPage />);

        const portrait = screen.getByRole('img', { name: '현재 전콘' });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/default.jpg`);

        fireEvent.change(screen.getByLabelText('이미지 서버'), { target: { value: '0' } });
        fireEvent.change(screen.getByLabelText('전콘 파일명'), { target: { value: '1001' } });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/1001.jpg`);

        fireEvent.change(screen.getByLabelText('전콘 파일명'), { target: { value: 'portrait.WEBP' } });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/portrait.WEBP`);

        fireEvent.change(screen.getByLabelText('전콘 파일명'), { target: { value: '' } });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/default.jpg`);
    });

    it('falls back once when the account portrait fails to load', () => {
        render(<AccountPage />);
        fireEvent.change(screen.getByLabelText('이미지 서버'), { target: { value: '0' } });
        fireEvent.change(screen.getByLabelText('전콘 파일명'), { target: { value: 'missing.png' } });

        const portrait = screen.getByRole('img', { name: '현재 전콘' }) as HTMLImageElement;
        const srcSetter = vi.spyOn(HTMLImageElement.prototype, 'src', 'set');
        try {
            fireEvent.error(portrait);
            expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/default.jpg`);
            expect(srcSetter).toHaveBeenCalledTimes(1);

            fireEvent.error(portrait);
            expect(srcSetter).toHaveBeenCalledTimes(1);
        } finally {
            srcSetter.mockRestore();
        }
    });

    it('renders the default for a whitespace-only account picture', () => {
        render(<AccountPage />);
        fireEvent.change(screen.getByLabelText('이미지 서버'), { target: { value: '0' } });
        fireEvent.change(screen.getByLabelText('전콘 파일명'), { target: { value: '   ' } });

        expect(screen.getByRole('img', { name: '현재 전콘' })).toHaveAttribute(
            'src',
            `${IMAGE_CDN_BASE}/icons/default.jpg`,
        );
    });

    it('confirms account deletion and redirects after logout', async () => {
        render(<AccountPage />);
        fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: 'oldpass' } });
        fireEvent.click(screen.getByRole('button', { name: '계정 삭제' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account', expect.objectContaining({
            method: 'DELETE',
            body: JSON.stringify({ currentPassword: 'oldpass' }),
        })));
        expect(logout).toHaveBeenCalled();
        expect(replace).toHaveBeenCalledWith('/');
    });
});
