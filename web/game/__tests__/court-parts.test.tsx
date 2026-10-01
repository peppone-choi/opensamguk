import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { CourtChoiceSheet, CourtDecisionList, DispatchSheet, IssuedDispatches, RewardPanel } from '../components/court/CourtParts';
import { courtChoices, dispatchCounties, dispatchPeople, issuedDispatches, rewardMoney, stripIdSuffix } from '../lib/court-view';
import type { DispatchOptionsResponse, DispatchPendingResponse } from '../lib/types';

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

test('포상 칸 — 상사 규칙(서버 상수)은 보이고 쓸 수 있는 금은 준비 중, 인물 · 금액을 골라야 접수, 몰수는 준비 중', () => {
    const onReward = vi.fn();
    render(<RewardPanel targets={[{ retainerId: 3, name: '무명 공조', loyalty: 40, picture: null, imageServer: 0 }]}
        reward={{ inputId: 'court.reward', status: 'AVAILABLE' }} confiscate={{ inputId: 'court.confiscate', status: 'NOT_DELIVERED' }}
        busy={false} onReward={onReward} onConfiscate={() => {}} />);
    expect(document.body).toHaveTextContent('금 100당 충성 +1 · 한 번에 최대 +10');
    expect(document.querySelector('[data-waiting="reward-usable"]')).toHaveTextContent('준비 중');
    expect(screen.getByRole('button', { name: '상사 — 접수' })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(screen.getByRole('option', { name: /무명 공조/ }));
    fireEvent.change(screen.getByRole('textbox', { name: '상사 금액' }), { target: { value: '300' } });
    fireEvent.click(screen.getByRole('button', { name: '상사 — 접수' }));
    expect(onReward).toHaveBeenCalledWith({ retainerId: 3, money: 300 });
    expect(screen.getByRole('button', { name: /몰수/ })).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
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
