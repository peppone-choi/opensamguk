// 서신 페이지(/game/mailbox) — 장수를 읽어 서신 화면을 붙이고, 없음 · 실패는 서신 화면 대신, 「새로고침」은 서신함과 받은 요청을 다시 읽는다.
import { configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import MailPage from '@/app/game/mailbox/page';
import { api } from '@/lib/api';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
vi.mock('@/lib/api', () => ({
    api: {
        frontInfo: vi.fn(), mailboxRecent: vi.fn(), mailboxOld: vi.fn(), generalsList: vi.fn(), contacts: vi.fn(),
        dispatchPending: vi.fn(), politicalConsentOptions: vi.fn(),
        commands: { sendMessage: vi.fn(), deleteMessage: vi.fn(), readLatestMessage: vi.fn() },
    },
}));
// main 의 옛 <Shell> 은 라우터를 쓰는 머리줄을 그린다(셸 #1107 뒤에는 통과 감싸기) — 옛 메일함 시험처럼 감싸기만 흉내 낸다.
vi.mock('@/components/Shell', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/RichTextEditor', () => ({
    RichTextEditor: ({ value, onChange, ariaLabel }: { value: string; onChange: (v: string) => void; ariaLabel: string }) =>
        <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />,
}));

const info = (general: Record<string, unknown>) => ({ general: { hasGeneral: true, generalId: 1, nationId: 3, ...general } });
const party = (id: number, name: string) => ({ id, name, nation_id: 3, nation: '[세력]', color: '#123456' });

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.frontInfo).mockResolvedValue(info({}) as never);
    vi.mocked(api.mailboxRecent).mockResolvedValue({
        private: [{ id: 12, msgType: 'private', src: party(2, '가'), dest: party(1, '나'), text: '받은 글', option: null, time: new Date().toISOString() }],
        public: [], national: [], sequence: 1,
    } as never);
    vi.mocked(api.generalsList).mockResolvedValue([] as never);
    vi.mocked(api.commands.readLatestMessage).mockResolvedValue({ status: 'AVAILABLE' } as never);
    vi.mocked(api.dispatchPending).mockResolvedValue({ result: true, dispatches: [] } as never);
    vi.mocked(api.politicalConsentOptions).mockResolvedValue([
        { inputId: 'action.oath', issuerGeneralId: 4, issuerName: '[의형]', available: true, accepted: null },
    ] as never);
});

test('제목 「서신」 · 탭 개인 · 세력 · 전체 · 요청(응답 대기 수) · 옛 외교 수락 · 거절 단추 없음', async () => {
    render(<MailPage />);
    expect(screen.getByRole('heading', { name: '서신' })).toBeInTheDocument();
    expect(await screen.findByRole('list', { name: '개인 서신' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['개인', '세력', '전체', '요청 1']);
    expect(screen.queryByRole('button', { name: /^(수락|거절)$/ })).toBeNull();
});

test('장수가 없으면 서신 화면 대신 안내 · 읽기 실패는 다시 시도', async () => {
    vi.mocked(api.frontInfo).mockResolvedValueOnce(info({ hasGeneral: false, generalId: null }) as never);
    const { unmount } = render(<MailPage />);
    expect(await screen.findByText('이 서버에 내 장수가 없습니다')).toBeInTheDocument();
    expect(api.mailboxRecent).not.toHaveBeenCalled();
    unmount();

    vi.mocked(api.frontInfo).mockRejectedValueOnce(new Error('500'));
    render(<MailPage />);
    expect(await screen.findByText('장수 정보를 불러오지 못했습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /다시/ }));
    expect(await screen.findByRole('list', { name: '개인 서신' })).toBeInTheDocument();
});

test('「새로고침」은 서신함과 받은 요청을 다시 읽는다', async () => {
    render(<MailPage />);
    await screen.findByRole('list', { name: '개인 서신' });
    await waitFor(() => expect(api.politicalConsentOptions).toHaveBeenCalled());
    const mail = vi.mocked(api.mailboxRecent).mock.calls.length;
    const req = vi.mocked(api.politicalConsentOptions).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }));
    await waitFor(() => expect(vi.mocked(api.mailboxRecent).mock.calls.length).toBeGreaterThan(mail));
    await waitFor(() => expect(vi.mocked(api.politicalConsentOptions).mock.calls.length).toBeGreaterThan(req));
});
