// 메일함 서신 삭제 배선 잠금 테스트 — legacy MessagePlate.vue tryDelete() 계약 + 실제 백엔드 계약.
//  - 발신자 본인 5분 이내 메시지에만 삭제 버튼이 노출된다(isMessageDeletable, 실제 게이팅 사용).
//  - confirm 취소 시 네트워크 요청이 발생하지 않는다.
//  - deleteMessage는 인테이크 명령이라 game-api가 precheck Blocked여도 202로 재라우팅한다.
//    엔진 deny/성공은 GET /api/command/result/{requestId}(RESOLVED + 톱레벨 ok/reason) 채널로만 온다
//    → 202 후 api.commandResult 폴링으로 성공/거부/미해결을 구분한다.
//  - RESOLVED ok → 재조회 / RESOLVED !ok → 엔진 reason 노출 + 재조회 없음 / 20회 미해결 → 접수 토스트 + 재조회.
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MailboxPage from '@/app/game/mailbox/page';
import type { MailboxMessage } from '@/types/game';

// 페이지 폴링 간격(page.tsx MESSAGE_RESULT_POLL_INTERVAL_MS)과 동일 — 이 지연만 즉시 발화시켜 실대기를 없앤다.
const POLL_INTERVAL_MS = 300;

const mocks = vi.hoisted(() => ({
    frontInfo: vi.fn(),
    mailboxRecent: vi.fn(),
    contacts: vi.fn(),
    readLatestMessage: vi.fn(),
    deleteMessage: vi.fn(),
    commandResult: vi.fn(),
    messageAccept: vi.fn(),
    messageDecline: vi.fn(),
}));

// @/lib/api 는 모킹하되, 인테이크 판별 가드는 실제 로직(status 기반)을 그대로 재현한다.
vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    Object.assign(actual.api, {
        frontInfo: mocks.frontInfo,
        mailboxRecent: mocks.mailboxRecent,
        contacts: mocks.contacts,
        commandResult: mocks.commandResult,
        messageAccept: mocks.messageAccept,
        messageDecline: mocks.messageDecline,
        commands: {
            deleteMessage: mocks.deleteMessage,
            readLatestMessage: mocks.readLatestMessage,
        },
    });
    return actual;
});

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: React.ReactNode }) => <section>{children}</section>,
}));
vi.mock('@/components/StatusBadge', () => ({
    default: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));

class EventSourceStub {
    onerror: (() => void) | null = null;
    addEventListener(): void {}
    close(): void {}
}
Object.defineProperty(globalThis, 'EventSource', { value: EventSourceStub, configurable: true });

// 발신자 본인(generalId 10)이 방금 보낸(5분 이내) 개인 메시지 — 삭제 버튼 노출 조건 충족.
const freshMessage = (): MailboxMessage => ({
    id: 55,
    mailbox: 10,
    type: 'private',
    src: 10,
    dest: 20,
    time: new Date().toISOString(),
    validUntil: new Date(Date.now() + 3_600_000).toISOString(),
    message: '',
    text: '테스트 메시지',
    srcTarget: { id: 10, name: '나', nationId: 1, nation: '촉', color: '#ffffff', icon: null },
    destTarget: null,
    option: {},
});

const diplomacyMessage = (): MailboxMessage => ({
    ...freshMessage(),
    id: 66,
    type: 'diplomacy',
    src: 20,
    dest: 10,
    text: '불가침 제안',
    option: { action: 'no_aggression' },
});

const recentEnvelope = (message: MailboxMessage) => {
    const target = (value: MailboxMessage['srcTarget']) => value == null ? null : {
        id: value.id,
        name: value.name,
        nation_id: value.nationId,
        nation: value.nation,
        color: value.color,
        icon: value.icon,
    };
    const item = {
        id: message.id,
        msgType: message.type,
        time: message.time,
        text: message.text,
        src: target(message.srcTarget),
        dest: target(message.destTarget),
        option: message.option,
    };
    return {
        private: message.type === 'private' ? [item] : [],
        national: [],
        public: [],
        diplomacy: message.type === 'diplomacy' ? [item] : [],
    };
};

beforeEach(() => {
    // 폴링 간격(300ms) 타이머만 즉시 발화 → 20회 폴링 실대기 제거. vi.useFakeTimers/attempt 축소 주입 미사용.
    // waitFor(50/1000ms)·토스트(3000ms) 등 다른 지연은 실제 타이머로 위임 → 단언 타이밍 보존.
    const realSetTimeout = globalThis.setTimeout;
    vi.spyOn(globalThis, 'setTimeout').mockImplementation((handler, timeout, ...args) =>
        realSetTimeout(handler, timeout === POLL_INTERVAL_MS ? 0 : timeout, ...args),
    );

    mocks.frontInfo.mockResolvedValue({ general: { generalId: 10, nationId: 1 } });
    mocks.mailboxRecent.mockResolvedValue(recentEnvelope(freshMessage()));
    mocks.contacts.mockResolvedValue({ nation: [] });
    mocks.readLatestMessage.mockResolvedValue({ status: 'AVAILABLE', requestId: 'read-latest' });
    mocks.deleteMessage.mockReset();
    mocks.commandResult.mockReset();
    mocks.messageAccept.mockReset();
    mocks.messageDecline.mockReset();
});

afterEach(() => {
    vi.restoreAllMocks();
});

describe('MailboxPage 서신 삭제', () => {
    it('confirm 취소 시 삭제 요청이 발생하지 않는다', async () => {
        window.confirm = vi.fn(() => false);
        render(<MailboxPage />);

        // 콜드스타트(첫 렌더) + frontInfo→mailbox 비동기 체인은 부하가 큰 러너에서 느릴 수 있어 넉넉한 타임아웃.
        const delBtn = await screen.findByRole('button', { name: '삭제' }, { timeout: 8000 });
        fireEvent.click(delBtn);

        expect(window.confirm).toHaveBeenCalledWith('삭제하시겠습니까?');
        expect(mocks.deleteMessage).not.toHaveBeenCalled();
        expect(mocks.commandResult).not.toHaveBeenCalled();
    });

    it('confirm 확인 + RESOLVED ok면 result 폴링 후 삭제 확정하고 재조회한다', async () => {
        window.confirm = vi.fn(() => true);
        mocks.deleteMessage.mockResolvedValueOnce({ status: 'AVAILABLE', requestId: 'del-1', turnIdx: 0 });
        mocks.commandResult.mockResolvedValue({ status: 'RESOLVED', requestId: 'del-1', ok: true, type: 'deleteMessage', result: {} });
        render(<MailboxPage />);

        const delBtn = await screen.findByRole('button', { name: '삭제' }, { timeout: 8000 });
        const mailboxCallsBefore = mocks.mailboxRecent.mock.calls.length;
        fireEvent.click(delBtn);

        await waitFor(() => expect(mocks.deleteMessage).toHaveBeenCalledWith({ msgID: 55 }, 10));
        await waitFor(() => expect(mocks.commandResult).toHaveBeenCalledWith('del-1'));
        // RESOLVED ok → 재조회(fetchMessages → api.mailbox 재호출) + 성공 토스트.
        await waitFor(() => expect(mocks.mailboxRecent.mock.calls.length).toBeGreaterThan(mailboxCallsBefore));
        expect(screen.getByText('서신을 삭제했습니다.')).toBeInTheDocument();
    });

    it('RESOLVED !ok면 엔진 reason을 노출하고 재조회하지 않는다', async () => {
        window.confirm = vi.fn(() => true);
        mocks.deleteMessage.mockResolvedValueOnce({ status: 'AVAILABLE', requestId: 'del-2', turnIdx: 0 });
        mocks.commandResult.mockResolvedValue({
            status: 'RESOLVED',
            requestId: 'del-2',
            ok: false,
            type: 'deleteMessage',
            reason: '5분 이내의 메시지만 삭제할 수 있습니다.',
            result: {},
        });
        render(<MailboxPage />);

        const delBtn = await screen.findByRole('button', { name: '삭제' }, { timeout: 8000 });
        await waitFor(() => expect(mocks.mailboxRecent).toHaveBeenCalled());
        const mailboxCallsBefore = mocks.mailboxRecent.mock.calls.length;
        fireEvent.click(delBtn);

        await waitFor(() => expect(mocks.commandResult).toHaveBeenCalledWith('del-2'));
        // 엔진 deny 사유(PHP byte-parity) 그대로 노출.
        await waitFor(() => expect(screen.getByText('5분 이내의 메시지만 삭제할 수 있습니다.')).toBeInTheDocument());
        // deny 경로는 재조회하지 않는다(목록 유지).
        expect(mocks.mailboxRecent.mock.calls.length).toBe(mailboxCallsBefore);
        expect(screen.getByText('테스트 메시지')).toBeInTheDocument();
    });

    it('20회 폴링 내 미해결(PENDING)이면 접수 토스트 + 재조회한다', async () => {
        window.confirm = vi.fn(() => true);
        mocks.deleteMessage.mockResolvedValueOnce({ status: 'AVAILABLE', requestId: 'del-3', turnIdx: 0 });
        mocks.commandResult.mockResolvedValue({ status: 'PENDING', requestId: 'del-3' });
        render(<MailboxPage />);

        const delBtn = await screen.findByRole('button', { name: '삭제' }, { timeout: 8000 });
        const mailboxCallsBefore = mocks.mailboxRecent.mock.calls.length;
        fireEvent.click(delBtn);

        // 폴링 20회 소진(300ms 타이머 즉시 발화 스텁으로 실대기 없음) 후 접수 처리.
        await waitFor(() => expect(mocks.commandResult).toHaveBeenCalledTimes(20), { timeout: 8000 });
        await waitFor(() => expect(screen.getByText('서신 삭제 요청을 접수했습니다.')).toBeInTheDocument());
        await waitFor(() => expect(mocks.mailboxRecent.mock.calls.length).toBeGreaterThan(mailboxCallsBefore));
    });
});

describe('MailboxPage 외교 서신', () => {
    it('수락은 RESOLVED ok 후에만 성공을 표시하고 재조회한다', async () => {
        // Given
        mocks.mailboxRecent.mockResolvedValue(recentEnvelope(diplomacyMessage()));
        mocks.messageAccept.mockResolvedValue({ status: 'AVAILABLE', requestId: 'accept-page-1' });
        mocks.commandResult.mockResolvedValue({
            status: 'RESOLVED',
            requestId: 'accept-page-1',
            ok: true,
            type: 'acceptDiplomaticMessage',
            result: {},
        });
        render(<MailboxPage />);
        fireEvent.click(await screen.findByRole('button', { name: '외교' }));
        const acceptButton = await screen.findByRole('button', { name: '수락' }, { timeout: 8000 });
        const mailboxCallsBefore = mocks.mailboxRecent.mock.calls.length;

        // When
        fireEvent.click(acceptButton);

        // Then
        await waitFor(() => expect(mocks.commandResult).toHaveBeenCalledWith('accept-page-1'));
        await waitFor(() => expect(screen.getByText('수락했습니다.')).toBeInTheDocument());
        await waitFor(() => expect(mocks.mailboxRecent.mock.calls.length).toBeGreaterThan(mailboxCallsBefore));
    });

    it('거절 RESOLVED deny는 엔진 사유를 그대로 노출하고 재조회하지 않는다', async () => {
        // Given
        mocks.mailboxRecent.mockResolvedValue(recentEnvelope(diplomacyMessage()));
        mocks.messageDecline.mockResolvedValue({ status: 'AVAILABLE', requestId: 'decline-page-1' });
        mocks.commandResult.mockResolvedValue({
            status: 'RESOLVED',
            requestId: 'decline-page-1',
            ok: false,
            type: 'declineDiplomaticMessage',
            reason: '이미 처리된 외교 서신입니다.',
            result: {},
        });
        render(<MailboxPage />);
        fireEvent.click(await screen.findByRole('button', { name: '외교' }));
        const declineButton = await screen.findByRole('button', { name: '거절' }, { timeout: 8000 });
        const mailboxCallsBefore = mocks.mailboxRecent.mock.calls.length;

        // When
        fireEvent.click(declineButton);

        // Then
        await waitFor(() => expect(screen.getByText('이미 처리된 외교 서신입니다.')).toBeInTheDocument());
        expect(mocks.mailboxRecent.mock.calls.length).toBe(mailboxCallsBefore);
    });

    it('수락 결과가 계속 PENDING이면 접수만 표시하고 재조회하지 않는다', async () => {
        // Given
        mocks.mailboxRecent.mockResolvedValue(recentEnvelope(diplomacyMessage()));
        mocks.messageAccept.mockResolvedValue({ status: 'AVAILABLE', requestId: 'accept-page-pending' });
        mocks.commandResult.mockResolvedValue({ status: 'PENDING', requestId: 'accept-page-pending' });
        render(<MailboxPage />);
        fireEvent.click(await screen.findByRole('button', { name: '외교' }));
        const acceptButton = await screen.findByRole('button', { name: '수락' }, { timeout: 8000 });
        const mailboxCallsBefore = mocks.mailboxRecent.mock.calls.length;

        // When
        fireEvent.click(acceptButton);

        // Then
        await waitFor(() => expect(mocks.commandResult).toHaveBeenCalledTimes(20), { timeout: 8000 });
        await waitFor(() => expect(screen.getByText('수락 요청을 접수했습니다.')).toBeInTheDocument());
        expect(screen.queryByText('수락했습니다.')).not.toBeInTheDocument();
        expect(mocks.mailboxRecent.mock.calls.length).toBe(mailboxCallsBefore);
    });
});
