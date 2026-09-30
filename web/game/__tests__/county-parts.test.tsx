import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { UNOWNED_NATION_NAME } from '@opensamguk/ui';
import { CountyGovernance, CountyHeader, CountyRecordsLink, CountySpecialties, CountyStock, HereActions } from '../components/county/CountyParts';
import { COUNTY_RECORDS_EMPTY, countyNation, specialtyRows, specialtyZeroReason } from '../lib/county-view';
import type { PolicyRow } from '../lib/territory-view';

test('보기 모델 — 주인 없음은 공용 상수 「무주」, 특산 0 · 읽지 못함, 0 인 까닭', () => {
    expect(countyNation(null)).toEqual({ label: UNOWNED_NATION_NAME, color: null });
    expect(countyNation({ id: 0, name: null, color: null }).label).toBe(UNOWNED_NATION_NAME);
    expect(countyNation({ id: 1, name: '조조', color: '#4f7fbf' })).toEqual({ label: '조조', color: '#4f7fbf' });
    const rows = specialtyRows({ status: 'READY', cityId: 2, name: '양적현', specialties: [
        { resource: 'IRON', label: '철', monthly: 0, ledgerMonthly: 12 },
        { resource: 'HORSE', label: '말', monthly: null },
    ] });
    expect(rows.map((r) => [r.monthly, r.ledger, r.zero])).toEqual([['월 0', '설계 월 12', true], ['읽지 못함', null, false]]);
    expect(specialtyZeroReason(false, null)).toContain('주인이 없는');
    expect(specialtyZeroReason(true, null)).toContain('창고가 없습니다');
    expect(specialtyZeroReason(true, { supplied: false })).toContain('끊긴');
});

test('머리 — 무주 · 고립 · 지금 여기 칩, 창고 없음 · 빈 창고 문장', () => {
    render(<CountyHeader name="양적현" commanderyName="영천군" nation={countyNation(null)} isolated here />);
    const head = screen.getByRole('banner');
    expect(head).toHaveTextContent(`양적현영천군${UNOWNED_NATION_NAME}고립지금 여기`);
    render(<CountyStock stock={null} />);
    expect(screen.getByText('이 현에는 창고가 없습니다.')).toBeInTheDocument();
    render(<CountyStock stock={{ money: 0, grain: 0, iron: 0, timber: 0, horses: 0 }} />);
    expect(screen.getByText('창고가 비었습니다.')).toBeInTheDocument();
});

test('특산 — 0 이 있을 때만 까닭 한 줄', () => {
    const rows = specialtyRows({ status: 'READY', cityId: 2, name: '양적현', specialties: [{ resource: 'IRON', label: '철', monthly: 0 }] });
    const { rerender } = render(<CountySpecialties rows={rows} zeroReason="이번 달 0 — 수도와 끊긴 현입니다." />);
    expect(screen.getByText('이번 달 0 — 수도와 끊긴 현입니다.')).toBeInTheDocument();
    rerender(<CountySpecialties rows={specialtyRows({ status: 'READY', cityId: 2, name: '양적현', specialties: [{ resource: 'IRON', label: '철', monthly: 5 }] })} zeroReason="이번 달 0 — 수도와 끊긴 현입니다." />);
    expect(screen.queryByText('이번 달 0 — 수도와 끊긴 현입니다.')).toBeNull();
});

test('다스림 — 빈자리면 「현령 앉히기」, 방침 대기 칩, 권한 없는 방침은 점선 + 사유, 우리 현이 아니면 읽기 문장', () => {
    const row: PolicyRow = { scope: 'COUNTY', targetId: '2', name: '양적현', sub: '영천군', seat: '빈자리', now: '농업', source: '기본', since: null,
        pending: '상업', lastApplied: null, hasActive: false, settable: false, blocked: { code: 'NOT_MAGISTRATE', reason: '현령이나 군주만 방침을 정합니다.' } };
    const onSeat = vi.fn();
    const { rerender } = render(<CountyGovernance policy={row}
        policyAvailability={{ inputId: 'policy.set', status: 'BLOCKED', reason: '현령이나 군주만 방침을 정합니다.' }} onChangePolicy={() => {}}
        seatAvailability={{ inputId: 'placement.assign', status: 'AVAILABLE' }} onSeat={onSeat} />);
    fireEvent.click(screen.getByRole('button', { name: '현령 앉히기' }));
    expect(onSeat).toHaveBeenCalledTimes(1);
    expect(screen.getByText('대기 — 다음 턴부터 상업')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /방침 바꾸기/ })).toHaveAttribute('aria-disabled', 'true');
    rerender(<CountyGovernance policy={null} policyAvailability={null} onChangePolicy={() => {}} seatAvailability={null} onSeat={() => {}} />);
    expect(screen.getByText('우리 현이 아니거나 다스릴 권한이 없는 현입니다.')).toBeInTheDocument();
});

test('사건 고리 · 여기서 할 행동 — 이 현에 없으면 한 줄 + 첩보만, 원장 행 없는 행동은 안 그린다', () => {
    render(<CountyRecordsLink href="/game/pep/records?cityId=2" empty />);
    expect(screen.getByRole('status')).toHaveTextContent(COUNTY_RECORDS_EMPTY);
    expect(screen.getByRole('link', { name: '이 현 기록 모두 보기 →' })).toHaveAttribute('href', '/game/pep/records?cityId=2');

    const items = [
        { inputId: 'action.farm', label: '농지개간', availability: { inputId: 'action.farm', status: 'AVAILABLE' as const }, onAct: () => {} },
        { inputId: 'action.nothing', label: '없는 행동', availability: null, onAct: () => {} },
    ];
    const scout = { inputId: 'action.scout', label: '첩보', availability: { inputId: 'action.scout', status: 'AVAILABLE' as const }, onAct: () => {} };
    const { rerender } = render(<HereActions items={items} here scout={scout} />);
    const list = screen.getByRole('list', { name: '여기서 할 수 있는 행동' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    rerender(<HereActions items={items} here={false} scout={scout} />);
    expect(screen.queryByRole('list', { name: '여기서 할 수 있는 행동' })).toBeNull();
    expect(screen.getByText('내정 · 군사 행동은 내 장수가 이 현에 있을 때만 할 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '첩보' })).toBeInTheDocument();
});
