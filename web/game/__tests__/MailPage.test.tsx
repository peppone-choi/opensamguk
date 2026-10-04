// 서신 페이지(/game/mailbox) — 셸 세션의 장수로 서신 화면을 붙이고(외교 탭 없음), 장수가 없으면 셸이 사유를 보이며,
// 「새로고침」은 서신함과 받은 요청을 같이 다시 읽는다.
import { configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import MailPage from '@/app/game/(campaign)/mail/page';
import { api } from '@/lib/api';
import { useGameSession, type GameSession } from '@/lib/campaign-session';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/mail',
    useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));
vi.mock('@/lib/api', () => ({
    api: {
        mailboxRecent: vi.fn(), mailboxOld: vi.fn(), generalsList: vi.fn(), contacts: vi.fn(),
        dispatchPending: vi.fn(), politicalConsentOptions: vi.fn(),
        commands: { sendMessage: vi.fn(), deleteMessage: vi.fn(), readLatestMessage: vi.fn() },
    },
}));
vi.mock('@/components/RichTextEditor', () => ({
    RichTextEditor: ({ value, onChange, ariaLabel }: { value: string; onChange: (v: string) => void; ariaLabel: string }) =>
        <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />,
}));

const party = (id: number, name: string) => ({ id, name, nation_id: 3, nation: '[세력]', color: '#123456' });

function setSession(generalId: number | null) {
    vi.mocked(useGameSession).mockReturnValue({
        loading: false, error: null, serverId: undefined, gameDate: '', refresh: vi.fn(), generalId,
        frontInfo: { general: { hasGeneral: generalId != null, generalId, nationId: 3, officerLevel: 1 } },
    } as unknown as GameSession);
}

beforeEach(() => {
    vi.clearAllMocks();
    setSession(1);
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

test('제목 「서신」 · 탭 개인 · 세력 · 전체 · 요청(응답 대기 수) · 외교 탭 · 옛 수락 · 거절 단추 없음', async () => {
    render(<MailPage />);
    expect(screen.getByRole('heading', { name: '서신' })).toBeInTheDocument();
    expect(await screen.findByRole('list', { name: '개인 서신' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['개인', '세력', '전체', '요청 1']));
    expect(screen.queryByRole('button', { name: /^(수락|거절)$/ })).toBeNull();
});

test('장수가 없으면 서신 화면 대신 셸이 사유를 보이고 서신함을 읽지 않는다', async () => {
    setSession(null);
    render(<MailPage />);
    expect(await screen.findByText(/장수가 없습니다/)).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: '개인 서신' })).toBeNull();
    expect(api.mailboxRecent).not.toHaveBeenCalled();
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
