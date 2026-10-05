// 서신 「도움 요청」(D68 · 원장 D111 · 보드 V31K6HelpRequest · MHelpRequest · MHelpStatus) — 서버 API 가 없어 화면 틀만 미리 짓는다.
// 양식: 종류 고르기 · 서버가 줄 칸과 결정 대기 다섯 칸은 「서버 대기」(H01 표지) · 본문은 전달만 · 보내기 「준비 중」(data-input-id 없음).
// 보낸 요청: 읽기가 없으면 서버 대기(빈 목록과 다름) · 여섯 단계 카드(시험 고정 자료만) · 「수락」 ≠ 도움 옴 · 출발함만 사건 고리.
// 쓰기 칸: 개인 서신 화면만 「글 서신 | 도움 요청」, 세력 서신 · 짧은 서신은 양식 없음. 요청 탭(서신 화면): 「받은 것 | 보낸 것」.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { expectServerWait } from '@opensamguk/ui';
import { HelpRequestCard, SentHelpRequests } from '../components/mail/HelpRequestCard';
import { HELP_BODY_NOTE, HelpRequestForm } from '../components/mail/HelpRequestForm';
import { MailRequestsPane } from '../components/mail/MailRequestsPane';
import { MailWrite } from '../components/mail/MailWrite';
import { HELP_STATES, helpStateLine, helpWhat, type HelpRequestView } from '../lib/mail/help-request';

// 글 서신 흉내 — 실제 MailCompose 처럼 쓰던 글을 자기 상태에 둔다(전환 때 내려갔다 올라오면 사라지는지 보려고).
vi.mock('../components/mail/MailCompose', async () => {
    const { useState } = await import('react');
    return {
        MailCompose: ({ scope, short }: { scope: string; short?: boolean }) => {
            const [draft, setDraft] = useState('');
            return (
                <div data-testid="letter" data-scope={scope} data-short={String(!!short)}>
                    <textarea aria-label="서신 내용" value={draft} onChange={(e) => setDraft(e.target.value)} />
                </div>
            );
        },
    };
});
vi.mock('../components/requests/IncomingRequests', () => ({ IncomingRequests: () => <div data-testid="incoming" /> }));

const view = (over: Partial<HelpRequestView> = {}): HelpRequestView => ({
    id: 'h1', to: '허저', kind: 'resource', asked: '쌀 300', target: '양적현', deadline: '200년 4월 상순', state: 'deciding',
    nextDecision: '200년 3월 하순', reason: null, arrival: null, eventHref: null, ...over,
});

describe('보기 모델', () => {
    it('단계 줄 — 서버 글자 그대로, 없으면 「서버 대기」 · 사유가 없으면 지어내지 않음, 수락은 출발 전', () => {
        expect(helpWhat(view())).toBe('자원 쌀 300 · 보낼 곳 양적현');
        expect(helpWhat(view({ kind: 'troops', asked: null, target: null }))).toBe('병력 서버 대기 · 보낼 곳 서버 대기');
        expect(helpStateLine(view())).toBe('허저 — 자기 순에 정해진 규칙으로 판단합니다 · 다음 판단 200년 3월 하순');
        expect(helpStateLine(view({ nextDecision: null }))).toContain('다음 판단 서버 대기');
        expect(helpStateLine(view({ state: 'waiting' }))).toBe('아직 정하지 않았습니다 — 사유를 받지 못했습니다');
        expect(helpStateLine(view({ state: 'accepted' }))).toBe('수락했습니다 — 아직 출발 전이라 도움이 온 것이 아닙니다');
        expect(helpStateLine(view({ state: 'departed', arrival: '200년 4월 중순' }))).toBe('출발했습니다 · 도착 예정 200년 4월 중순');
        expect(helpStateLine(view({ state: 'rejected', reason: '창고가 비었습니다' }))).toBe('거절했습니다 — 창고가 비었습니다');
        expect(helpStateLine(view({ state: 'closed' }))).toBe('닫혔습니다');
    });
});

describe('도움 요청 양식', () => {
    it('서버가 줄 칸 · 결정 대기 다섯 칸은 서버 대기(H01) — 종류를 병력으로 바꾸면 부곡 단위 칸', () => {
        const { container } = render(<HelpRequestForm />);
        const kinds = within(screen.getByRole('group', { name: '도움 종류' }));
        expect(kinds.getByRole('button', { name: '자원' })).toHaveAttribute('aria-pressed', 'true');
        expectServerWait(container, ['H01 · 받는 사람', 'H01 · 받는 사람 범위', 'H01 · 자원 · 상한', 'H01 · 보낼 곳', 'H01 · 기한', 'H01 · 판단 규칙 · 빈도']);
        fireEvent.click(kinds.getByRole('button', { name: '병력' }));
        expect(kinds.getByRole('button', { name: '병력' })).toHaveAttribute('aria-pressed', 'true');
        expectServerWait(container, ['H01 · 받는 사람', 'H01 · 받는 사람 범위', 'H01 · 병력 단위', 'H01 · 보낼 곳', 'H01 · 기한', 'H01 · 판단 규칙 · 빈도']);
        expect(screen.getByText('병력은 부곡 단위로 청한다 — 숫자 병력은 없다')).toBeInTheDocument();
    });

    it('본문은 전달만(안내가 본문 칸 설명) · 보내기는 「준비 중」 — 비활성, data-input-id 없음', () => {
        const { container } = render(<HelpRequestForm />);
        const body = screen.getByRole('textbox', { name: '본문(선택)' });
        expect(body).toHaveAccessibleDescription(HELP_BODY_NOTE);
        fireEvent.change(body, { target: { value: '장사현 창고가 비었소.' } });
        expect(body).toHaveValue('장사현 창고가 비었소.');
        const send = screen.getByRole('button', { name: '도움 요청 보내기' });
        expect(send).toHaveAttribute('aria-disabled', 'true');
        expect(send).toHaveAccessibleDescription(/^준비 중/);
        expect(container.querySelector('[data-input-id]')).toBeNull();
    });
});

describe('보낸 도움 요청', () => {
    it('읽기가 없으면 서버 대기(H01 · 보낸 요청 읽기) — 빈 목록과 다르다', () => {
        const a = render(<SentHelpRequests items={null} />);
        expectServerWait(a.container, ['H01 · 보낸 요청 읽기']);
        a.unmount();
        render(<SentHelpRequests items={[]} />);
        expect(screen.getByText('보낸 도움 요청이 없습니다')).toBeInTheDocument();
    });

    it('여섯 단계 카드 — 종류 칩 · 받는 사람 · 기한 · 청한 것 · 상태 칩 + 한 줄, 응답 단추 없음, 출발함만 사건 고리', () => {
        const items = HELP_STATES.map((state, i) => view({ id: `h${i}`, state, reason: '창고가 비었습니다', arrival: '200년 4월 중순', eventHref: '/game/records?event=9' }));
        render(<SentHelpRequests items={items} />);
        const cards = within(screen.getByRole('list', { name: '보낸 도움 요청' })).getAllByRole('listitem');
        expect(cards).toHaveLength(6);
        expect(cards.map((c) => within(c).getAllByText(/^(판단 대기|대기|수락|출발함|거절|기한 지남 · 취소됨)$/)[0].textContent)).toEqual(
            ['판단 대기', '대기', '수락', '출발함', '거절', '기한 지남 · 취소됨'],
        );
        expect(within(cards[0]).getByText('도움 요청')).toBeInTheDocument();
        expect(within(cards[0]).getByText('허저')).toBeInTheDocument();
        expect(within(cards[0]).getByText('기한 200년 4월 상순')).toBeInTheDocument();
        expect(within(cards[2]).getByText('수락했습니다 — 아직 출발 전이라 도움이 온 것이 아닙니다')).toBeInTheDocument();
        expect(screen.getAllByRole('link', { name: '사건 보기' })).toHaveLength(1);
        expect(within(cards[3]).getByRole('link', { name: '사건 보기' })).toHaveAttribute('href', '/game/records?event=9');
        expect(screen.queryByRole('button', { name: /수락|거절/ })).toBeNull();
    });

    it('「단계 보기」를 펴면 여섯 단계 중 지금 단계에 aria-current="step"', () => {
        render(<HelpRequestCard item={view({ state: 'accepted' })} />);
        const toggle = screen.getByRole('button', { name: '단계 보기' });
        expect(toggle).toHaveAttribute('aria-expanded', 'false');
        fireEvent.click(toggle);
        expect(screen.getByRole('button', { name: '단계 접기' })).toHaveAttribute('aria-expanded', 'true');
        const steps = within(screen.getByRole('list', { name: '도움 요청 단계 — 서버가 준 단계만' })).getAllByRole('listitem');
        expect(steps).toHaveLength(6);
        expect(steps.filter((s) => s.getAttribute('aria-current') === 'step').map((s) => s.textContent)).toEqual(['수락']);
    });
});

describe('서신 쓰기 칸 · 요청 탭', () => {
    const me = { generalId: 7, nationId: 1, name: '하후돈' } as never;

    it('개인 서신(서신 화면)만 「글 서신 | 도움 요청」 — 고르면 양식, 세력 서신 · 짧은 서신은 글 서신만', () => {
        const a = render(<MailWrite me={me} scope="private" />);
        const kinds = within(screen.getByRole('group', { name: '서신 종류' }));
        expect(kinds.getByRole('button', { name: '글 서신' })).toHaveAttribute('aria-pressed', 'true');
        expect(screen.getByTestId('letter')).toBeInTheDocument();
        fireEvent.click(kinds.getByRole('button', { name: '도움 요청' }));
        expect(screen.getByTestId('letter').parentElement).not.toBeVisible();
        expect(screen.getByTestId('help-request-form')).toBeVisible();
        a.unmount();
        const b = render(<MailWrite me={me} scope="national" />);
        expect(screen.queryByRole('group', { name: '서신 종류' })).toBeNull();
        b.unmount();
        render(<MailWrite me={me} scope="private" short />);
        expect(screen.queryByRole('group', { name: '서신 종류' })).toBeNull();
        expect(screen.getByTestId('letter')).toHaveAttribute('data-short', 'true');
    });

    it('「글 서신 ↔ 도움 요청」을 오가도 쓰던 글 서신 · 도움 요청 본문이 남는다 — 고르지 않은 쪽은 숨김(hidden)', () => {
        render(<MailWrite me={me} scope="private" />);
        const kinds = within(screen.getByRole('group', { name: '서신 종류' }));
        fireEvent.change(screen.getByRole('textbox', { name: '서신 내용' }), { target: { value: '곧 가겠습니다' } });
        fireEvent.click(kinds.getByRole('button', { name: '도움 요청' }));
        // 숨긴 글 서신은 접근성 트리에서 빠진다(누를 영역 · 이름 검색에도 안 걸림).
        expect(screen.queryByRole('textbox', { name: '서신 내용' })).toBeNull();
        fireEvent.change(screen.getByRole('textbox', { name: '본문(선택)' }), { target: { value: '쌀을 조금 보내 주게' } });
        fireEvent.click(kinds.getByRole('button', { name: '글 서신' }));
        expect(screen.getByRole('textbox', { name: '서신 내용' })).toHaveValue('곧 가겠습니다');
        expect(screen.queryByRole('textbox', { name: '본문(선택)' })).toBeNull();
        fireEvent.click(kinds.getByRole('button', { name: '도움 요청' }));
        expect(screen.getByRole('textbox', { name: '본문(선택)' })).toHaveValue('쌀을 조금 보내 주게');
    });

    it('요청 탭 — 서신 화면은 「받은 것 | 보낸 것」(보낸 것 = 서버 대기), 머리줄 서랍은 받은 요청 + 조정 고리', () => {
        const a = render(<MailRequestsPane generalId={7} variant="page" />);
        const box = within(screen.getByRole('group', { name: '요청 — 받은 것 · 보낸 것' }));
        expect(screen.getByTestId('incoming')).toBeInTheDocument();
        fireEvent.click(box.getByRole('button', { name: '보낸 것' }));
        expect(screen.queryByTestId('incoming')).toBeNull();
        expectServerWait(a.container, ['H01 · 보낸 요청 읽기']);
        a.unmount();
        render(<MailRequestsPane generalId={7} variant="header" courtHref="/game/court?tab=appointments" />);
        expect(screen.queryByRole('group', { name: '요청 — 받은 것 · 보낸 것' })).toBeNull();
        expect(screen.getByRole('link', { name: '조정에서 모두 보기 →' })).toHaveAttribute('href', '/game/court?tab=appointments');
    });
});
