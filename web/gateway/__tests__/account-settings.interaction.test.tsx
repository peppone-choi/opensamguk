import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
    replace: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
    user: { id: 1, username: 'tester', email: null, nickname: null as string | null, role: 'USER', picture: 'old.png', imageServer: 1 },
}));

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: mocks.replace }) }));
vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
// 대표 장수 구획은 자기 fetch(/api/account/representative)를 갖는다 — 이 테스트의 fetch 스파이 계약(별명 · 초상)과 분리한다.
vi.mock('@/components/account/RepresentativeSection', () => ({ default: () => null }));
vi.mock('@/lib/auth-context', () => {
    const session = () => ({ user: mocks.user, refresh: mocks.refresh, logout: mocks.logout });
    return { useAuth: session, useAuthOptional: session };
});

import AccountPage from '@/app/account/page';
import { IMAGE_CDN_BASE } from '@/lib/constants';
import { DEFAULT_PORTRAIT } from '@/lib/portrait';

function response(status = 200, body = '{}'): Response {
    return new Response(body, { status, headers: { 'Content-Type': 'application/json' } });
}

// jsdom의 file input에 파일을 얹는다 (직접 .files 대입은 막혀 defineProperty로 우회).
async function selectFile(file: File): Promise<void> {
    const input = screen.getByLabelText('초상 이미지 파일') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    fireEvent.change(input);
    await waitFor(() => expect(screen.queryByText('원본을 불러오는 중…')).toBeNull());
}

/** 올리기는 세 구도를 한 번씩 본 뒤에 열린다(보드 MAccount 사유 시트). */
function viewAllCrops(): void {
    fireEvent.click(screen.getByRole('radio', { name: '카드' }));
    fireEvent.click(screen.getByRole('radio', { name: '아이콘' }));
}

function iconFile(bytes: number, name = 'icon.png', type = 'image/png'): File {
    return new File([new Uint8Array(bytes)], name, { type });
}

// Browser image decoding: the crop editor uses oriented natural dimensions.
function stubBitmap(width: number, height: number): void {
    vi.stubGlobal('Image', class {
        naturalWidth = width; naturalHeight = height;
        onload: (() => void) | null = null;
        set src(_value: string) { queueMicrotask(() => this.onload?.()); }
    });
}

// 피드백은 그 액션을 일으킨 컨트롤과 같은 패널 안에서만 떠야 한다(화면 밖 전역 배너 금지).
function panel(heading: string): HTMLElement {
    return screen.getByRole('heading', { name: heading }).closest('section') as HTMLElement;
}

function button(name: string, scope: HTMLElement = document.body): HTMLElement {
    return within(scope).getByRole('button', { name });
}

function expectBlocked(name: string, reason: string, scope?: HTMLElement): void {
    const target = button(name, scope);
    expect(target).toHaveAttribute('aria-disabled', 'true');
    expect(target).toHaveAttribute('data-reason', reason);
}

function uploadedForm(): FormData {
    const init = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls[0][1];
    return init.body as FormData;
}

function savedSourceFetch(cropsId = 'aabbccdd.portrait'): void {
    const saved = { hero: { x: 0.1, y: 0.1, width: 0.3, height: 0.3 * 900 / 633 }, card: { x: 0, y: 0, width: 148 / 210, height: 1 }, icon: { x: 0.2, y: 0.2, width: 0.5, height: 0.5 } };
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(url.endsWith('/source')
        ? new Response(new Blob(['source'], { type: 'image/png' }), { headers: { 'Content-Type': 'image/png', 'X-Portrait-Id': 'aabbccdd.portrait' } })
        : new Response(JSON.stringify(saved), { headers: { 'Content-Type': 'application/json', 'X-Portrait-Id': cropsId } }))));
}

describe('account settings interactions', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mocks.refresh.mockResolvedValue(null);
        mocks.logout.mockResolvedValue(undefined);
        mocks.user = { id: 1, username: 'tester', email: null, nickname: null, role: 'USER', picture: 'old.png', imageServer: 1 };
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response()));
        URL.createObjectURL = vi.fn(() => 'blob:portrait');
        URL.revokeObjectURL = vi.fn();
        stubBitmap(96, 96);
    });

    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it('drops the shared-icon filename form and keeps every field in the v3.1 field style', () => {
        render(<AccountPage />);

        expect(screen.getByRole('heading', { level: 1, name: '계정 설정' })).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '로비로' })).toHaveAttribute('href', '/lobby');
        for (const [heading, label] of [['별명 바꾸기', '별명'], ['비밀번호 바꾸기', '현재 비밀번호'], ['비밀번호 바꾸기', '새 비밀번호'],
            ['비밀번호 바꾸기', '새 비밀번호 확인'], ['초상', '초상 이미지 파일'], ['계정 탈퇴', '현재 비밀번호']] as const) {
            expect(within(panel(heading)).getByLabelText(label).closest('.gw31-field')).not.toBeNull();
        }
        // A22–A25: 삼모 공유 이미지 서버 파일명 · 서버 선택 · 저장은 없다.
        expect(screen.queryByLabelText(/파일명/)).toBeNull();
        expect(screen.queryByLabelText('이미지 서버')).toBeNull();
        expect(screen.queryByText(/전콘|닉네임|히어로/)).toBeNull();
    });

    it('changes the password with a confirmation field and reports it inside the password panel', async () => {
        render(<AccountPage />);
        const form = panel('비밀번호 바꾸기');
        expectBlocked('바꾸기', '현재 비밀번호를 쓰세요', form);
        fireEvent.change(within(form).getByLabelText('현재 비밀번호'), { target: { value: 'oldpass' } });
        expectBlocked('바꾸기', '새 비밀번호를 쓰세요', form);
        fireEvent.change(within(form).getByLabelText('새 비밀번호'), { target: { value: 'new' } });
        expectBlocked('바꾸기', '비밀번호는 6자 이상이어야 합니다', form);
        fireEvent.change(within(form).getByLabelText('새 비밀번호'), { target: { value: 'newpass1' } });
        fireEvent.change(within(form).getByLabelText('새 비밀번호 확인'), { target: { value: 'newpass2' } });
        expectBlocked('바꾸기', '비밀번호가 서로 다릅니다.', form);
        expect(within(form).getByLabelText('새 비밀번호 확인')).toHaveAttribute('aria-invalid', 'true');
        fireEvent.click(button('바꾸기', form));
        expect(fetch).not.toHaveBeenCalled();

        fireEvent.change(within(form).getByLabelText('새 비밀번호 확인'), { target: { value: 'newpass1' } });
        fireEvent.click(button('바꾸기', form));
        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/password', expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ currentPassword: 'oldpass', newPassword: 'newpass1' }),
        })));
        expect(await within(form).findByRole('status')).toHaveTextContent('비밀번호를 바꿨습니다.');
        expect(within(form).getByLabelText('현재 비밀번호')).toHaveValue('');
        expect(within(panel('초상')).queryByRole('status')).toBeNull();
    });

    it('shows the server sentence when the password change is refused', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(400, JSON.stringify({ error: '현재 비밀번호가 맞지 않습니다.' }))));
        render(<AccountPage />);
        const form = panel('비밀번호 바꾸기');
        fireEvent.change(within(form).getByLabelText('현재 비밀번호'), { target: { value: 'wrong' } });
        fireEvent.change(within(form).getByLabelText('새 비밀번호'), { target: { value: 'newpass1' } });
        fireEvent.change(within(form).getByLabelText('새 비밀번호 확인'), { target: { value: 'newpass1' } });
        fireEvent.click(button('바꾸기', form));
        expect(await within(form).findByRole('alert')).toHaveTextContent('현재 비밀번호가 맞지 않습니다.');
    });

    it('changes the nickname and refreshes the header session immediately', async () => {
        const updated = { id: 1, username: 'tester', email: null, nickname: '새별명', role: 'USER', picture: 'old.png', imageServer: 1 };
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, JSON.stringify({ user: updated }))));
        render(<AccountPage />);
        fireEvent.change(screen.getByLabelText('별명'), { target: { value: '  새별명  ' } });
        fireEvent.click(button('별명 바꾸기', panel('별명 바꾸기')));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/nickname', expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ nickname: '새별명' }),
        })));
        expect(mocks.refresh).toHaveBeenCalledWith(updated);
        expect(await within(panel('별명 바꾸기')).findByRole('status')).toHaveTextContent('별명을 바꿨습니다.');
        expect(screen.getByText('2~20자, 다른 사람과 겹칠 수 없습니다.')).toBeInTheDocument();
    });

    it('opens 올리기 only after all three crops were viewed, then uploads the original byte-for-byte', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, JSON.stringify({
            id: 1, username: 'tester', email: null, nickname: null, role: 'USER', picture: 'a1b2c3d4.png', imageServer: 1,
        }))));
        const original = iconFile(2048);
        render(<AccountPage />);
        expectBlocked('올리기', '올릴 이미지 파일을 고르세요');
        await selectFile(original);
        expectBlocked('올리기', '세 구도를 확인하세요');
        fireEvent.click(button('올리기'));
        expect(fetch).not.toHaveBeenCalled();
        viewAllCrops();
        fireEvent.click(button('올리기'));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/profile-icon', expect.objectContaining({ method: 'POST' })));
        const init = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls[0][1];
        expect(uploadedForm().get('file')).toBe(original);
        expect(Object.keys(JSON.parse(uploadedForm().get('crops') as string))).toEqual(['hero', 'card', 'icon']);
        // 헤더에 Authorization/토큰을 프론트에서 붙이지 않는다 — 프록시가 서버측 쿠키로만 처리.
        expect(init.headers).toBeUndefined();
        expect(await within(panel('초상')).findByRole('status')).toHaveTextContent('초상을 올렸습니다.');
        expect(screen.getByRole('img', { name: '지금 초상' })).toHaveAttribute('src', '/d_pic/a1b2c3d4.png');
    });

    it.each([
        ['50KB 초과 원본', 51_201, 96, 96],
        ['큰 원본', 2048, 4000, 3000],
        ['비정사각형', 2048, 64, 128],
    ] as const)('sends three user-adjustable crops for a %s', async (_label, bytes, w, h) => {
        stubBitmap(w, h);
        const file = iconFile(bytes);
        render(<AccountPage />);
        await selectFile(file);
        viewAllCrops();
        fireEvent.click(button('올리기'));
        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/profile-icon', expect.objectContaining({ method: 'POST' })));
        expect(uploadedForm().get('file')).toBe(file);
    });

    it('rejects oversized originals before upload and gives the limit as the reason', async () => {
        render(<AccountPage />);
        await selectFile(iconFile(8 * 1024 * 1024 + 1));
        const alert = await within(panel('초상')).findByRole('alert');
        expect(alert).toHaveTextContent('8MB 이하');
        expectBlocked('올리기', alert.textContent ?? '');
        expect(fetch).not.toHaveBeenCalled();
    });

    it('rejects originals below the supported dimensions', async () => {
        stubBitmap(32, 32);
        render(<AccountPage />);
        await selectFile(iconFile(2048));
        expect(await within(panel('초상')).findByRole('alert')).toHaveTextContent('64~8192px');
        expect(fetch).not.toHaveBeenCalled();
    });

    it.each([
        [409, '프로필 아이콘은 하루에 한 번만 변경할 수 있습니다.'],
        [400, '올바른 프로필 아이콘 이미지가 아닙니다.'],
        [401, '로그인이 필요합니다.'],
    ] as const)('shows the server sentence on %s and keeps the existing portrait', async (status, sentence) => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(status, JSON.stringify({ error: sentence }))));
        render(<AccountPage />);
        const before = screen.getByRole('img', { name: '지금 초상' }).getAttribute('src');
        await selectFile(iconFile(2048));
        viewAllCrops();
        fireEvent.click(button('올리기'));

        const alert = await within(panel('초상')).findByRole('alert');
        expect(alert).toHaveTextContent(sentence);
        expect(alert.textContent).not.toMatch(/Bearer|eyJ/);
        expect(screen.queryByText('초상을 올렸습니다.')).toBeNull();
        expect(screen.getByRole('img', { name: '지금 초상' })).toHaveAttribute('src', before!);
    });

    it('reopens the retained source with its own saved crop positions, already checked', async () => {
        mocks.user = { ...mocks.user, picture: 'aabbccdd.portrait', imageServer: 1 };
        savedSourceFetch();
        render(<AccountPage />);
        fireEvent.click(button('보관된 원본으로 다시 편집'));
        const preview = await screen.findByAltText('아이콘 저장 미리보기');
        expect(preview.style.left).toBe('-40%');
        expect(button('올리기')).not.toHaveAttribute('aria-disabled');
    });

    it('rejects mismatched original and crop identities instead of editing a mixed version', async () => {
        mocks.user = { ...mocks.user, picture: 'aabbccdd.portrait', imageServer: 1 };
        savedSourceFetch('bbccddee.portrait');
        render(<AccountPage />);
        fireEvent.click(button('보관된 원본으로 다시 편집'));
        expect(await within(panel('초상')).findByRole('alert')).toHaveTextContent('다른 창에서 초상이 바뀌었습니다');
        expect(screen.queryByLabelText('큰 그림 확대·축소')).toBeNull();
    });

    it('asks before deleting the uploaded portrait and converges to the default', async () => {
        render(<AccountPage />);
        fireEvent.click(button('지우기'));
        const dialog = await screen.findByRole('dialog', { name: '초상 지우기' });
        expect(fetch).not.toHaveBeenCalled();
        fireEvent.click(within(dialog).getByRole('button', { name: '지우기' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/profile-icon', expect.objectContaining({ method: 'DELETE' })));
        expect(await within(panel('초상')).findByRole('status')).toHaveTextContent('초상을 지웠습니다.');
        expect(screen.getByRole('img', { name: '지금 초상' })).toHaveAttribute('src', DEFAULT_PORTRAIT);
    });

    it('keeps a saved shared portrait visible but explains why it cannot be deleted', () => {
        mocks.user = { ...mocks.user, picture: '1001', imageServer: 0 };
        render(<AccountPage />);
        expect(screen.getByRole('img', { name: '지금 초상' })).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/1001.jpg`);
        expectBlocked('지우기', '올린 초상이 없습니다');
        expect(screen.queryByRole('button', { name: '보관된 원본으로 다시 편집' })).toBeNull();
    });

    it('renders the default for a whitespace-only picture and falls back once on a load error', () => {
        mocks.user = { ...mocks.user, picture: '   ', imageServer: 0 };
        const { unmount } = render(<AccountPage />);
        expect(screen.getByRole('img', { name: '지금 초상' })).toHaveAttribute('src', DEFAULT_PORTRAIT);
        unmount();

        mocks.user = { ...mocks.user, picture: 'missing.png', imageServer: 0 };
        render(<AccountPage />);
        const portrait = screen.getByRole('img', { name: '지금 초상' }) as HTMLImageElement;
        const srcSetter = vi.spyOn(HTMLImageElement.prototype, 'src', 'set');
        try {
            fireEvent.error(portrait);
            expect(portrait).toHaveAttribute('src', DEFAULT_PORTRAIT);
            expect(srcSetter).toHaveBeenCalledTimes(1);
            fireEvent.error(portrait);
            expect(srcSetter).toHaveBeenCalledTimes(1);
        } finally {
            srcSetter.mockRestore();
        }
    });

    it('deletes the account with its own password field after the confirm dialog, then shows the login notice', async () => {
        render(<AccountPage />);
        const quit = panel('계정 탈퇴');
        expectBlocked('계정 삭제', '현재 비밀번호를 쓰세요', quit);
        // 비밀번호 패널의 칸은 탈퇴에 쓰이지 않는다(A9).
        fireEvent.change(within(panel('비밀번호 바꾸기')).getByLabelText('현재 비밀번호'), { target: { value: 'other' } });
        expectBlocked('계정 삭제', '현재 비밀번호를 쓰세요', quit);
        fireEvent.change(within(quit).getByLabelText('현재 비밀번호'), { target: { value: 'oldpass' } });
        fireEvent.click(button('계정 삭제', quit));

        const dialog = await screen.findByRole('dialog', { name: '계정 탈퇴' });
        expect(dialog).toHaveTextContent('계정을 삭제하면 되돌릴 수 없습니다. 현재 비밀번호로 탈퇴하시겠습니까?');
        expect(fetch).not.toHaveBeenCalled();
        fireEvent.click(within(dialog).getByRole('button', { name: '계정 삭제' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account', expect.objectContaining({
            method: 'DELETE',
            body: JSON.stringify({ currentPassword: 'oldpass' }),
        })));
        await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith('/login?notice=account-deleted'));
        // logout() 은 `/login` 으로 강제 이동해 표지를 버린다 — 쿠키는 탈퇴 라우트가 지운다.
        expect(mocks.logout).not.toHaveBeenCalled();
    });

    it('keeps the account and shows the server sentence when deletion is refused', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(401, JSON.stringify({ error: '비밀번호가 올바르지 않습니다.' }))));
        render(<AccountPage />);
        const quit = panel('계정 탈퇴');
        fireEvent.change(within(quit).getByLabelText('현재 비밀번호'), { target: { value: 'wrong' } });
        fireEvent.click(button('계정 삭제', quit));
        fireEvent.click(within(await screen.findByRole('dialog', { name: '계정 탈퇴' })).getByRole('button', { name: '계정 삭제' }));

        expect(await within(quit).findByRole('alert')).toHaveTextContent('비밀번호가 올바르지 않습니다.');
        expect(screen.queryByRole('dialog')).toBeNull();
        expect(mocks.logout).not.toHaveBeenCalled();
        expect(mocks.replace).not.toHaveBeenCalled();
    });
});
