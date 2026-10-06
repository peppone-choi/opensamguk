import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AUTH_LABELS } from '@/lib/constants';

const mocks = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn(), register: vi.fn(), servers: [] as { id: string; name: string }[] }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: mocks.push, refresh: mocks.refresh }) }));
vi.mock('@/lib/client', () => ({ register: mocks.register }));
vi.mock('@/lib/serverRegistry', () => ({ getServers: () => mocks.servers }));
vi.mock('@/components/MapPreview', () => ({
    default: ({ serverId, variant }: { serverId?: string; variant?: string }) => <div data-testid="map" data-server={serverId} data-variant={variant} />,
}));

vi.mock('@/lib/serverPublication', async () => {
    // 공개 목록(C8)은 이 시험의 레지스트리 흉내에서 만든다 — 비었고 「유효한 빈 표」가 아니면 원천 불명(UNKNOWN)
    const registry = await import('@/lib/serverRegistry');
    return {
        readPublicServers: async () => {
            const servers = registry.getServers();
            const validEmpty = 'isValidEmptyServerRegistry' in registry ? registry.isValidEmptyServerRegistry() : true;
            if (servers.length === 0 && !validEmpty) return { kind: 'unknown' };
            return { kind: 'known', servers: servers.map((s) => ({ id: s.id, name: s.name, generation: s.generation ?? null, gameUrl: s.gameUrl ?? `/game/${s.id}` })) };
        },
    };
});
import JoinPage from '@/app/join/page';
import { checkJoin } from '@/components/join/JoinForm';

function fill(values: Partial<Record<'username' | 'password' | 'passwordConfirm' | 'nickname' | 'email', string>>) {
    const labels = { username: AUTH_LABELS.username, password: AUTH_LABELS.password, passwordConfirm: AUTH_LABELS.passwordConfirm, nickname: AUTH_LABELS.nickname, email: /이메일/ } as const;
    for (const [key, value] of Object.entries(values)) {
        fireEvent.change(screen.getByLabelText(labels[key as keyof typeof labels], { exact: true }), { target: { value } });
    }
}
const submit = () => fireEvent.click(screen.getByRole('button', { name: AUTH_LABELS.registerBtn }));

beforeEach(() => {
    mocks.push.mockReset();
    mocks.register.mockReset();
    mocks.servers = [{ id: 'pep', name: 'pep' }];
});

describe('P-G03 가입 — 화면', () => {
    it('로그인과 같은 지도 배경 · 입력 5칸과 도움말 · 이메일 「선택」 · 로그인 링크 둘 · 정책 링크 · 로고 한 번', async () => {
        render(await JoinPage());
        expect(screen.getByTestId('map')).toHaveAttribute('data-variant', 'backdrop');
        expect(screen.getByTestId('map')).toHaveAttribute('data-server', 'pep');
        expect(screen.getByRole('heading', { level: 1, name: '회원 가입' })).toBeInTheDocument();
        expect(screen.getByText('3~50자')).toBeInTheDocument();
        expect(screen.getByText('6자 이상')).toBeInTheDocument();
        expect(screen.getByText('2~20자, 다른 사람과 겹칠 수 없습니다.')).toBeInTheDocument();
        expect(screen.getByLabelText(/이메일/)).toHaveAttribute('type', 'email');
        expect(screen.getByText('선택')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login');
        expect(screen.getByRole('link', { name: AUTH_LABELS.toLogin })).toHaveAttribute('href', '/login');
        expect(within(screen.getByRole('navigation', { name: '정책' })).getByRole('link', { name: '이용약관' })).toHaveAttribute('href', '/terms');
        // 로고는 둘을 그리고 CSS 가 폭마다 하나만 보인다(D88): 머리줄 로고(1199 이하) · 소개 묶음 큰 워드마크(1200 이상). 보이는 수는 스모크가 잰다.
        expect(screen.getAllByAltText('오픈삼국')).toHaveLength(2);
        expect(within(screen.getByRole('banner', { name: '상단바' })).getByAltText('오픈삼국')).toHaveClass('os-brand');
        const logo = within(screen.getByRole('region', { name: '계정 안내' })).getByAltText('오픈삼국');
        expect(logo).toHaveAttribute('src', '/logo-wordmark.png');
        expect(logo.closest('picture')?.querySelector('source[type="image/webp"]')).toHaveAttribute('srcset', '/logo-wordmark.webp');
        for (const text of [/계정은 한 번 만들면/, /이용이 막힐 수 있습니다/]) expect(screen.getByText(text)).toHaveAttribute('data-copy-status', 'approved');
        expect(screen.queryByText(/문구 초안/)).not.toBeInTheDocument();
    });

    it('서버가 하나도 없으면 지도 대신 바탕만 깐다', async () => {
        mocks.servers = [];
        render(await JoinPage());
        expect(screen.queryByTestId('map')).toBeNull();
        expect(screen.getByRole('heading', { level: 1, name: '회원 가입' })).toBeInTheDocument();
    });
});

describe('P-G03 가입 — 제출 전 검사(쉬운 말, 그 칸 아래에)', () => {
    it.each([
        [{}, 'username', '계정명을 입력하세요'],
        [{ username: 'ab' }, 'username', '계정명은 3자 이상이어야 합니다'],
        [{ username: 'a'.repeat(51) }, 'username', '계정명은 50자를 넘을 수 없습니다'],
        [{ username: 'hahoudon', password: '12345' }, 'password', '비밀번호는 6자 이상이어야 합니다'],
        [{ username: 'hahoudon', password: '123456', passwordConfirm: '1234567' }, 'passwordConfirm', '비밀번호가 서로 다릅니다.'],
        [{ username: 'hahoudon', password: '123456', passwordConfirm: '123456', nickname: ' ' }, 'nickname', '별명을 입력하세요'],
        [{ username: 'hahoudon', password: '123456', passwordConfirm: '123456', nickname: '원' }, 'nickname', '별명은 2자 이상이어야 합니다'],
        [{ username: 'hahoudon', password: '123456', passwordConfirm: '123456', nickname: '원'.repeat(21) }, 'nickname', '별명은 20자를 넘을 수 없습니다'],
    ])('%o → %s 「%s」', (values, field, message) => {
        expect(checkJoin({ username: '', password: '', passwordConfirm: '', nickname: '', ...values })).toEqual({ field, message });
    });

    it('비밀번호가 다르면 확인 칸 아래 오류 · aria-invalid · 그 칸으로 초점, 서버는 부르지 않는다', async () => {
        render(await JoinPage());
        fill({ username: 'hahoudon', password: 'secret1', passwordConfirm: 'secret2', nickname: '원양' });
        submit();
        const confirm = screen.getByLabelText(AUTH_LABELS.passwordConfirm);
        expect(confirm).toHaveAttribute('aria-invalid', 'true');
        expect(screen.getByRole('alert')).toHaveTextContent('비밀번호가 서로 다릅니다.');
        expect(confirm).toHaveAttribute('aria-describedby', screen.getByRole('alert').id);
        expect(document.activeElement).toBe(confirm);
        expect(mocks.register).not.toHaveBeenCalled();
    });

    it('표시 단추는 비밀번호 두 칸을 함께 보인다', async () => {
        render(await JoinPage());
        fireEvent.click(screen.getByRole('button', { name: '표시' }));
        expect(screen.getByLabelText(AUTH_LABELS.password, { exact: true })).toHaveAttribute('type', 'text');
        expect(screen.getByLabelText(AUTH_LABELS.passwordConfirm)).toHaveAttribute('type', 'text');
    });
});

describe('P-G03 가입 — 제출', () => {
    const valid = { username: 'hahoudon', password: 'secret1', passwordConfirm: 'secret1', nickname: '원양' };

    it('서버 거절 문장은 받은 그대로 제출 단추 위에', async () => {
        mocks.register.mockRejectedValueOnce(new Error('이미 사용 중인 아이디입니다'));
        render(await JoinPage());
        fill(valid);
        submit();
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('이미 사용 중인 아이디입니다'));
        expect(mocks.push).not.toHaveBeenCalled();
    });

    it('성공하면 로비로 — 이메일은 비면 보내지 않는다', async () => {
        mocks.register.mockResolvedValueOnce({});
        render(await JoinPage());
        fill({ ...valid, username: '  hahoudon  ' });
        submit();
        await waitFor(() => expect(mocks.push).toHaveBeenCalledWith('/lobby'));
        expect(mocks.register).toHaveBeenCalledWith({ username: 'hahoudon', password: 'secret1', email: undefined, nickname: '원양' });
    });
});
