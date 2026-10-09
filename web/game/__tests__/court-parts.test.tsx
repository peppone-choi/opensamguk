import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { CourtChoiceSheet, CourtDecisionList, DispatchSheet, IssuedDispatches, RewardPanel } from '../components/court/CourtParts';
import { parseRewardOptions } from '../lib/api/court-reward';
import { rewardMoney, rewardPanelView } from '../lib/court-reward-view';
import { DISPATCH_BLOCKED_FALLBACK, courtChoices, dispatchCounties, dispatchPeople, issuedDispatches, stripIdSuffix } from '../lib/court-view';
import type { DispatchOptionsResponse, DispatchPendingResponse } from '../lib/types';
import { card, readyBody, unavailableFunding } from './fixtures/court-reward';

const phase = { year: 200, month: 3, phase: 2 };
const pending: DispatchPendingResponse = {
    result: true,
    dispatches: [
        { dispatchId: 'd1', issuerId: 7, targetId: 21, countyId: 129, targetLabel: '순욱', countyLabel: '양성현', issuedAt: phase, dueAt: { year: 200, month: 4, phase: 1 }, status: 'PENDING' },
        { dispatchId: 'd2', issuerId: 7, targetId: 22, countyId: 130, targetLabel: null, countyLabel: null, issuedAt: phase, dueAt: phase, status: 'REFUSED' },
        { dispatchId: 'd3', issuerId: 1, targetId: 7, countyId: 131, issuedAt: phase, dueAt: phase, status: 'PENDING' },
    ],
};
const options: DispatchOptionsResponse = {
    result: true,
    targets: [{ generalId: 21, label: '순욱' }],
    counties: [
        { countyId: 129, label: '양성현', available: true },
        { countyId: 130, label: '허현', available: false, code: 'OCCUPIED', reason: '이미 현령이 있습니다.' },
    ],
};

test('보기 모델 — 내린 발령만(받은 것은 K6 카드), 라벨 없으면 id 대신 「이름 모름」, 조정 라벨 id 꼬리 떼기, 금액 검사', () => {
    const rows = issuedDispatches(pending, 7);
    expect(rows.map((r) => [r.target, r.county, r.status, r.due])).toEqual([
        ['순욱', '양성현', '응답 대기', '200년 4월 상순'],
        ['이름 모를 장수', '이름 모를 현', '거절', null],
    ]);
    expect(rows.map((r) => r.blocked)).toEqual([null, null]);
    expect(dispatchPeople(options)).toEqual([{ generalId: 21, name: '순욱', isHuman: true, groups: ['mine'] }]);
    expect(dispatchCounties(options).map((c) => [c.targetId, c.available, c.reason ?? null])).toEqual([['129', true, null], ['130', false, '이미 현령이 있습니다.']]);
    expect(stripIdSuffix('영천 군단 (42)')).toBe('영천 군단');
    expect(stripIdSuffix('허현(수도)')).toBe('허현(수도)');
    expect(courtChoices({ inputId: 'court.moveCapital', available: true, choices: [
        { label: '진류현 (41)', arguments: { cityId: 41 }, available: false, reason: '' },
    ] })[0]).toMatchObject({ label: '진류현', available: false, reason: '사유를 받지 못했습니다' });
    expect([rewardMoney('300'), rewardMoney('0'), rewardMoney('1.5'), rewardMoney(' 12 ')]).toEqual([300, null, null, 12]);
});

test('발령 칸 — 목록 · 접수 대기 칩 · 사람 장수 없음 안내, 주공이 아니면 「새 발령」 점선 + 사유', () => {
    render(<IssuedDispatches rows={issuedDispatches(pending, 7)} queued="발령을 접수했습니다 — 주공의 다음 개인 턴에 처리합니다." noPeople
        territoryHref="/game/pep/territory" availability={{ inputId: 'court.dispatch', status: 'BLOCKED', reason: '발령은 주공만 할 수 있습니다.' }} onNew={() => {}} />);
    expect(within(screen.getByRole('list', { name: '내린 발령' })).getAllByRole('listitem')[0]).toHaveTextContent('순욱 → 양성현');
    expect(screen.getByRole('link', { name: '영지 →' })).toHaveAttribute('href', '/game/pep/territory');
    expect(screen.getByRole('button', { name: /새 발령/ })).toHaveAttribute('aria-disabled', 'true');
    expect(document.body).toHaveTextContent('발령은 주공만 할 수 있습니다.');
});

test('새 발령 시트 — 사람 → 현(불가는 사유) → 보낸다, 고르기 전엔 막힘', () => {
    const onSubmit = vi.fn();
    const onTargetChange = vi.fn();
    const { rerender } = render(<DispatchSheet people={dispatchPeople(options)} target={null} onTargetChange={onTargetChange} counties={null}
        busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    expect(screen.getByRole('button', { name: '이 현으로 발령' })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(screen.getByRole('option', { name: /순욱/ }));
    expect(onTargetChange).toHaveBeenCalledWith(21);
    rerender(<DispatchSheet people={dispatchPeople(options)} target={21} onTargetChange={onTargetChange} counties={dispatchCounties(options)}
        busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    const list = screen.getByRole('listbox', { name: '발령할 현' });
    expect(within(list).getByRole('option', { name: /허현/ })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(within(list).getByRole('option', { name: /양성현/ }));
    fireEvent.click(screen.getByRole('button', { name: '이 현으로 발령' }));
    expect(onSubmit).toHaveBeenCalledWith({ targetGeneralId: 21, countyId: 129 });
});

test('포상 칸 — 서버 보기 그대로(규칙 · 창고 금 · 미리 보기 · 막는 까닭), 고르기 · 금액 · 접수는 위로 알리기만, 몰수는 준비 중', () => {
    const cards = [card(3, 40), card(4, 0, { funding: unavailableFunding('RECIPIENT_MISSING') })];
    const optionsFor = (retainerId: number | null, money: number | null) => {
        const query = { generalId: 7, retainerId, money };
        const r = parseRewardOptions(readyBody({ cards }, { ...query, money: money == null ? null : String(money) }), query);
        if (r?.status !== 'READY') throw new Error('고정 응답이 계약을 어겼다');
        return r;
    };
    const handlers = { onSelect: vi.fn(), onAmountChange: vi.fn(), onSubmit: vi.fn(), onRetry: vi.fn() };
    const avail = { reward: { inputId: 'court.reward', status: 'AVAILABLE' } as const, confiscate: { inputId: 'court.confiscate', status: 'NOT_DELIVERED' } as const };
    const idle = rewardPanelView({ options: optionsFor(null, null), retinue: null, selected: null, money: null, preview: { state: 'idle' } });
    const { rerender } = render(<RewardPanel view={idle} selected={null} amount="" busy={false} {...avail} {...handlers} onConfiscate={() => {}} />);
    expect(document.body).toHaveTextContent('금 100당 충성 +1 · 한 번에 최대 +10');
    expect(document.body).toHaveTextContent('200년 3월 중순에 저장된 값으로 낸 추정치입니다');
    expect(screen.getByRole('option', { name: /이름 모를 인물/ })).toBeInTheDocument();
    expect(document.querySelector('[data-reward-usable]')).toBeNull();
    expect(screen.getByRole('button', { name: '상사 — 접수' })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(screen.getByRole('option', { name: /인물3/ }));
    expect(handlers.onSelect).toHaveBeenCalledWith(3);
    fireEvent.change(screen.getByRole('textbox', { name: '상사 금액' }), { target: { value: '0300' } });
    expect(handlers.onAmountChange).toHaveBeenCalledWith('0300');

    const options = optionsFor(3, 300);
    const ready = rewardPanelView({ options, retinue: null, selected: 3, money: 300, preview: { state: 'ready', preview: options.preview! } });
    rerender(<RewardPanel view={ready} selected={3} amount="0300" busy={false} {...avail} {...handlers} onConfiscate={() => {}}
        notice={{ tone: 'ok', text: '상사를 접수했습니다 — 다음 개인 턴에 처리합니다.' }} />);
    expect(screen.getByRole('option', { name: /인물3/ })).toHaveAttribute('aria-selected', 'true');
    expect(document.querySelector('[data-reward-usable="known"]')).toHaveTextContent('금 5,120');
    const status = screen.getByRole('status', { name: '상사 미리 보기' });
    expect(status).toHaveTextContent('충성 +3 · 상사 뒤 충성 43');
    expect(status).toHaveTextContent('조회 시점 창고로 지급 가능 · 실행 때 다시 확인');
    expect(status).toHaveTextContent('접수 가능 여부 · 상사 이력 · 동시 차감 · 조회 이후 상태');
    expect(screen.getByText('상사를 접수했습니다 — 다음 개인 턴에 처리합니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '상사 — 접수' }));
    expect(handlers.onSubmit).toHaveBeenCalledTimes(1);
    rerender(<RewardPanel view={ready} selected={3} amount="0300" busy {...avail} {...handlers} onConfiscate={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '상사 — 접수' }));
    expect(handlers.onSubmit).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: /몰수/ })).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
    expect(document.body).toHaveTextContent('봉록은 달마다 저절로 나갑니다.');

    const failed = rewardPanelView({ options, retinue: null, selected: 3, money: 300, preview: { state: 'error' } });
    rerender(<RewardPanel view={failed} selected={3} amount="0300" busy={false} {...avail} {...handlers} onConfiscate={() => {}} refreshFailed />);
    expect(screen.getByRole('button', { name: '상사 — 접수' })).toHaveAttribute('aria-disabled', 'true');
    expect(document.body).toHaveTextContent('상사 선택지를 다시 불러오지 못했습니다.');
    fireEvent.click(screen.getAllByRole('button', { name: '다시 시도' })[0]);
    expect(handlers.onRetry).toHaveBeenCalled();
});

test('조정 명령 시트 · 모바일 목록 — 불가 선택지는 사유, 원장 행 없는 입력은 그리지 않는다', () => {
    const onSubmit = vi.fn();
    render(<CourtChoiceSheet inputId="court.moveCapital" title="천도" busy={false} onSubmit={onSubmit} onCancel={() => {}}
        choices={courtChoices({ inputId: 'court.moveCapital', available: true, choices: [
            { label: '진류현 (41)', arguments: { cityId: 41 }, available: true },
            { label: '허현 (3)', arguments: { cityId: 3 }, available: false, reason: '이미 수도입니다.' },
        ] })} />);
    const list = screen.getByRole('listbox', { name: '천도 대상' });
    expect(within(list).getByRole('option', { name: /허현/ })).toHaveTextContent('이미 수도입니다.');
    fireEvent.click(within(list).getByRole('option', { name: '진류현' }));
    fireEvent.click(screen.getByRole('button', { name: '이대로 접수' }));
    expect(onSubmit).toHaveBeenCalledWith({ cityId: 41 });

    const onOpen = vi.fn();
    render(<CourtDecisionList items={[
        { inputId: 'court.dispatchReply', name: '발령 응답', desc: '받은 발령에 답합니다', availability: { inputId: 'court.dispatchReply', status: 'AVAILABLE' }, waiting: 2, onOpen },
        { inputId: 'court.abandonCounty', name: '현 포기', desc: '내 현을 버립니다', availability: { inputId: 'court.abandonCounty', status: 'BLOCKED', reason: '현 포기는 군주만 할 수 있습니다.' }, onOpen },
        { inputId: 'court.diplomacy', name: '외교', desc: '다른 세력과', availability: { inputId: 'court.diplomacy', status: 'NOT_DELIVERED' }, onOpen },
        { inputId: 'court.recommend', name: '천도 건의', desc: '원장 행 없음', availability: null, onOpen },
    ]} />);
    const rows = within(screen.getByRole('list', { name: '조정 결정' })).getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('응답 대기 2');
    expect(rows[1]).toHaveTextContent('현 포기는 군주만 할 수 있습니다.');
    expect(rows[2]).toHaveTextContent('준비 중');
    fireEvent.click(within(rows[0]).getByRole('button'));
    expect(onOpen).toHaveBeenCalledTimes(1);
});

test('내린 발령이 응답 대기인데 막혔으면 — 서버 문장(currentFailureReason)을 그대로, 없으면 옛 문구, 대기가 아니면 없음', () => {
    const base = pending.dispatches[0];
    const rows = issuedDispatches({ ...pending, dispatches: [
        { ...base, dispatchId: 'a', currentFailure: 'NOT_DIRECT_RETAINER', currentFailureReason: '직속 부하가 아니라 이 발령을 받을 수 없습니다.' },
        { ...base, dispatchId: 'b', currentFailure: 'NOT_DIRECT_RETAINER', currentFailureReason: null },
        { ...base, dispatchId: 'c', currentFailure: 'NOT_DIRECT_RETAINER' },
        { ...base, dispatchId: 'd', currentFailure: 'NOT_DIRECT_RETAINER', currentFailureReason: '   ' },
        { ...base, dispatchId: 'e', status: 'REFUSED', currentFailure: 'NOT_DIRECT_RETAINER', currentFailureReason: '무시' },
        { ...base, dispatchId: 'f', currentFailure: null, currentFailureReason: '무시' },
    ] }, 7);
    expect(rows.map((r) => r.blocked)).toEqual([
        '직속 부하가 아니라 이 발령을 받을 수 없습니다.', DISPATCH_BLOCKED_FALLBACK, DISPATCH_BLOCKED_FALLBACK, DISPATCH_BLOCKED_FALLBACK, null, null,
    ]);
    render(<IssuedDispatches rows={rows.slice(0, 2)} queued={null} availability={null} onNew={() => {}} />);
    expect(screen.getByRole('list', { name: '내린 발령' })).toHaveTextContent('직속 부하가 아니라 이 발령을 받을 수 없습니다.');
    expect(screen.getByRole('list', { name: '내린 발령' })).toHaveTextContent(DISPATCH_BLOCKED_FALLBACK);
});
