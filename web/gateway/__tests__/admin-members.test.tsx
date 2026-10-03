import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MemberControl from '@/components/admin/MemberControl';

const user = (id: number, username: string, extra: Record<string, unknown> = {}) => ({
    id, username, email: `${username}@example.com`, authType: 'local', grade: 1, gradeLabel: '일반', blockUntil: null, nickname: `${username}별명`,
    icon: null, joinDate: '2026-09-01T10:00:00', lastLoginAt: '2026-10-01T09:00:00', deleteAfter: null, generalNamesByServer: {}, ...extra,
});
const USERS = [
    user(1, 'spam01'),
    user(2, 'boss', { grade: 6, gradeLabel: '운영자' }),
    user(3, 'blocked', { grade: 0, gradeLabel: '차단', blockUntil: '2027-01-01T00:00:00', email: null }),
];
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function fetchFake() {
    return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (path === '/api/proxy/admin/users') return json({ users: USERS, servers: ['pep'], allowJoin: true, allowLogin: true });
        if (path === '/api/proxy/admin/system/allow_join') return json({ allowJoin: JSON.parse(String(init?.body)).value, allowLogin: true });
        // 실제 서버 계약(AdminMemberService): detail 은 값이 아니라 문장이다.
        if (path === '/api/proxy/admin/users/1/reset_pw') return json({ result: true, detail: '비밀번호가 Tmp9f3로 초기화되었습니다.' });
        if (path === '/api/proxy/admin/users/1/block') return json({ result: true });
        if (path === '/api/proxy/admin/users/1/delete') return json({ result: true });
        return json({}, 404);
    });
}
const posts = () => (fetch as unknown as { mock: { calls: [string, RequestInit?][] } }).mock.calls.filter(([, init]) => init?.method === 'POST');
const row = (name: string) => screen.getByText(name, { selector: 'b' }).closest('tr') as HTMLElement;
const openActions = async (name: string) => {
    fireEvent.click(within(row(name)).getByRole('button', { name: '조치' }));
    return screen.findByRole('dialog', { name: `${name} 조치` });
};

describe('P-G09 운영 콘솔 — 회원', () => {
    beforeEach(() => vi.stubGlobal('fetch', fetchFake()));
    afterEach(() => vi.unstubAllGlobals());

    it('열 이름 · 상태(차단 만료일 연도까지) · 서버 대기 · 뺀 삼모 등급', async () => {
        render(<MemberControl />);
        const table = await screen.findByRole('table');
        expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
            '번호', '계정명', '이메일', '상태', '별명', '초상', '서버별 장수', '가입일', '최근 로그인', '탈퇴 예정', '조치',
        ]);
        expect(within(row('boss')).getByText('운영자')).toBeInTheDocument();
        expect(within(row('blocked')).getByText('차단 · 2027-01-01까지')).toBeInTheDocument();
        expect(within(row('spam01')).getByText('서버 대기')).toBeInTheDocument();
        expect(within(row('spam01')).getByText('2026-09-01')).toBeInTheDocument();
        expect(screen.queryByText(/^(특별|부운영자|전콘|닉네임)$/)).toBeNull();
        expect(screen.queryByRole('button', { name: '별도 권한' })).toBeNull();
    });

    it('상태 거르기 · 계정명/별명 찾기', async () => {
        render(<MemberControl />);
        await screen.findByRole('table');
        fireEvent.click(screen.getByRole('radio', { name: '차단' }));
        expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2);
        fireEvent.click(screen.getByRole('radio', { name: '전체' }));
        fireEvent.change(screen.getByRole('searchbox', { name: '계정명 · 별명으로 찾기' }), { target: { value: 'boss별' } });
        expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2);
        fireEvent.change(screen.getByRole('searchbox', { name: '계정명 · 별명으로 찾기' }), { target: { value: '없는사람' } });
        expect(screen.getByText('조건에 맞는 회원이 없습니다.')).toBeInTheDocument();
    });

    it('새 가입을 막을 때만 확인을 받는다', async () => {
        render(<MemberControl />);
        await screen.findByRole('table');
        const join = screen.getByRole('radiogroup', { name: '새 가입 받기' });
        fireEvent.click(within(join).getByRole('radio', { name: '막음' }));
        const dialog = await screen.findByRole('dialog', { name: '새 가입 막기' });
        expect(posts()).toHaveLength(0);
        fireEvent.click(within(dialog).getByRole('button', { name: '막기' }));
        await waitFor(() => expect(within(join).getByRole('radio', { name: '막음' })).toHaveAttribute('aria-checked', 'true'));
        expect(posts()[0][0]).toBe('/api/proxy/admin/system/allow_join');
        fireEvent.click(within(join).getByRole('radio', { name: '받음' }));
        await waitFor(() => expect(posts()).toHaveLength(2));
        expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('차단은 일수 칸 대화상자(prompt 금지)로, 기본 7일', async () => {
        render(<MemberControl />);
        await screen.findByRole('table');
        fireEvent.click(within(await openActions('spam01')).getByRole('button', { name: /^차단/ }));
        const dialog = await screen.findByRole('dialog', { name: 'spam01 차단' });
        expect(within(dialog).getByLabelText('차단 일수')).toHaveValue(7);
        expect(dialog).toHaveTextContent('spam01 계정을 차단합니다(7일). 계속할까요?');
        fireEvent.change(within(dialog).getByLabelText('차단 일수'), { target: { value: '0' } });
        expect(dialog).toHaveTextContent('spam01 계정을 차단합니다(영구).');
        fireEvent.change(within(dialog).getByLabelText('차단 일수'), { target: { value: '30' } });
        fireEvent.click(within(dialog).getByRole('button', { name: '차단' }));
        await waitFor(() => expect(posts()[0][0]).toBe('/api/proxy/admin/users/1/block'));
        expect(posts()[0][1]?.body).toBe(JSON.stringify({ param: 30 }));
    });

    it('임시 비밀번호는 결과 창에만 보이고 알림 줄에 남지 않는다', async () => {
        render(<MemberControl />);
        await screen.findByRole('table');
        fireEvent.click(within(await openActions('spam01')).getByRole('button', { name: /임시 비밀번호 발급/ }));
        fireEvent.click(within(await screen.findByRole('dialog', { name: '임시 비밀번호 발급' })).getByRole('button', { name: '발급' }));
        const result = await screen.findByRole('dialog', { name: 'spam01 임시 비밀번호' });
        // 칸에는 문장이 아니라 비밀번호만 — 그대로 전해도 로그인된다.
        expect(within(result).getByRole('status', { name: '임시 비밀번호' })).toHaveTextContent(/^Tmp9f3$/);
        // 화면 어디에도(알림 줄 포함) 한 번만 — 결과 창 안에만 있다.
        expect(screen.getAllByText(/Tmp9f3/)).toHaveLength(1);
        // 복사도 비밀번호만 클립보드에 넣는다.
        const writeText = vi.fn(async () => undefined);
        Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
        fireEvent.click(within(result).getByRole('button', { name: '복사' }));
        await within(result).findByText('복사했습니다.');
        expect(writeText).toHaveBeenCalledWith('Tmp9f3');
        fireEvent.click(within(result).getByRole('button', { name: '닫기' }));
        await waitFor(() => expect(screen.queryByText(/Tmp9f3/)).toBeNull());
    });

    it('detail 모양이 계약과 다르면 문장을 그대로 보이고 복사를 사유와 함께 잠근다', async () => {
        const base = fetchFake();
        vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
            String(input) === '/api/proxy/admin/users/1/reset_pw' ? json({ result: true, detail: '새 비밀번호를 메일로 보냈습니다.' }) : base(input, init)));
        render(<MemberControl />);
        await screen.findByRole('table');
        fireEvent.click(within(await openActions('spam01')).getByRole('button', { name: /임시 비밀번호 발급/ }));
        fireEvent.click(within(await screen.findByRole('dialog', { name: '임시 비밀번호 발급' })).getByRole('button', { name: '발급' }));
        const result = await screen.findByRole('dialog', { name: 'spam01 임시 비밀번호' });
        expect(within(result).getByText('새 비밀번호를 메일로 보냈습니다.')).toBeInTheDocument();
        expect(within(result).queryByRole('status', { name: '임시 비밀번호' })).toBeNull();
        expect(within(result).getByRole('button', { name: '복사' })).toHaveAttribute('aria-disabled', 'true');
    });

    it('이메일이 없으면 영구 차단은 사유와 함께 잠기고, 강제 탈퇴는 되돌릴 수 없다고 묻는다', async () => {
        render(<MemberControl />);
        await screen.findByRole('table');
        const sheet = await openActions('blocked');
        expect(within(sheet).getByRole('button', { name: /이메일 영구 차단/ })).toHaveAttribute('data-reason', '이메일이 없는 계정입니다');
        expect(within(sheet).getByRole('button', { name: /차단 풀기/ })).toBeInTheDocument();
        fireEvent.click(within(sheet).getByRole('button', { name: '닫기' }));
        fireEvent.click(within(await openActions('spam01')).getByRole('button', { name: /강제 탈퇴/ }));
        const confirm = await screen.findByRole('dialog', { name: '강제 탈퇴' });
        expect(confirm).toHaveTextContent('spam01 계정을 강제로 탈퇴시킵니다. 되돌릴 수 없습니다.');
        fireEvent.click(within(confirm).getByRole('button', { name: '강제 탈퇴' }));
        await waitFor(() => expect(posts()[0][0]).toBe('/api/proxy/admin/users/1/delete'));
    });
});
