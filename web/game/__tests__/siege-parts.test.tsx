import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { FortOrders, SiegeDetail, SiegeList, SiegeOrders, fortKey, siegeKey } from '../components/siege/SiegeParts';
import type { RoadFort, Siege } from '../lib/campaign-reads';
import { fortRows, siegeRows, siegeStatus } from '../lib/siege-view';

const siege = (over: Partial<Siege> = {}): Siege => ({
    countyId: 41, countyName: '진류현', status: 'ACTIVE', endReason: null,
    besieger: { generalId: 7, name: '하후돈', nationId: 1, nationName: '조조' }, defenderNationId: 2, defenderNationName: '원소',
    startedAt: { year: 200, month: 2, phase: 3 }, turns: 2, grain: null, morale: 6500, garrison: 1200, trust: 40,
    countySupplied: true, besiegerTroops: 3000, besiegerFed: false, canAct: true, surrenderDemandAccepted: false,
    timeline: [{ year: 200, month: 2, phase: 3, event: 'START' }, { year: 200, month: 3, phase: 1, event: 'NEW_CODE', morale: 6000, garrison: 1100 }],
    ...over,
});
const fort: RoadFort = { id: 'f-1', edgeId: 'e-9', provinceId: 'p-44', row: 12, col: 30, ownerNationId: 2, wall: 800, garrison: 200,
    besiegerGeneralId: null, siegeProgress: 0, canBesiege: false };

test('보기 모델 — 모르는 상태 · 사건은 원문 대신, 이름 없으면 id 대신 「이름 모를 현」, 보이지 않는 값은 「?」', () => {
    const [a] = siegeRows([siege()]);
    expect(a.cells.map((c) => `${c.key} ${c.value}`)).toEqual([
        '성 안 수비 1,200명', '성 안 사기 65%', '성 안 쌀 ?', '민심 40', '포위 병력 3,000명', '포위군 급식 못 받음',
    ]);
    expect(a.timeline).toEqual([
        { when: '200년 2월 하순', what: '포위 시작' },
        { when: '200년 3월 상순', what: '공성 사건 · 사기 60% · 수비 1,100' },
    ]);
    expect(siegeStatus('WEIRD')).toEqual({ label: '알 수 없음', tone: 'neutral' });
    const [b] = siegeRows([siege({ countyName: null, besieger: { generalId: 7, name: null, nationId: 1, nationName: null } })]);
    expect(b.title).toBe('이름 모를 현');
    expect(b.commander).toBe('이름 모를 장수');
    expect([b.title, b.sides, b.commander].join(' ')).not.toMatch(/\d/);
    expect(fortRows([fort])[0]).toEqual({ id: 'f-1', wall: 800, garrison: 200, progress: null, canBesiege: false });
});

test('목록 — 성과 보루를 한 목록에, 고르면 알린다, 보루는 구역 id · 좌표 · 세력 숫자를 보이지 않는다', () => {
    const onSelect = vi.fn();
    render(<SiegeList sieges={siegeRows([siege()])} forts={fortRows([fort])} selected={siegeKey(41)} onSelect={onSelect} />);
    const [s, f] = within(screen.getByRole('listbox', { name: '포위' })).getAllByRole('option');
    expect(s).toHaveAttribute('aria-selected', 'true');
    expect(s).toHaveTextContent('조조 → 원소 · 포위 2순째');
    expect(f).toHaveTextContent('도로 보루');
    expect(f).not.toHaveTextContent('p-44');
    expect(f).not.toHaveTextContent('12, 30');
    fireEvent.click(f);
    expect(onSelect).toHaveBeenCalledWith(fortKey('f-1'));
});

test('빈 목록 — 「포위 중인 성이 없습니다」 + 군단으로', () => {
    render(<SiegeList sieges={[]} forts={[]} selected={null} onSelect={() => {}} corpsHref="/game/pep/corps" />);
    expect(screen.getByRole('status')).toHaveTextContent('포위 중인 성이 없습니다');
    expect(screen.getByRole('link', { name: '군단으로 →' })).toHaveAttribute('href', '/game/pep/corps');
});

test('상세 · 명령 — 급식 못 받음 경고, 강공 막힘은 서버 사유, 항복 권고 조건, 함락되면 4줄, 끝난 포위는 명령 없음', () => {
    const [row] = siegeRows([siege()]);
    render(<SiegeDetail row={row} />);
    expect(screen.getByText('못 받음 — 병력이 줄어듭니다')).toBeInTheDocument();
    const onSurrender = vi.fn();
    const { rerender } = render(
        <SiegeOrders row={row} assault={{ inputId: 'action.assault', status: 'BLOCKED', code: 'ASSAULT_NOT_READY', reason: '포위 3순째부터 강공할 수 있습니다.' }}
            surrender={{ inputId: 'action.demandSurrender', status: 'AVAILABLE' }} onAssault={() => {}} onSurrender={onSurrender} />,
    );
    const orders = screen.getByRole('region', { name: '명령' });
    expect(within(orders).getByRole('button', { name: /강공/ })).toHaveAttribute('aria-disabled', 'true');
    expect(orders).toHaveTextContent('포위 3순째부터 강공할 수 있습니다.');
    fireEvent.click(within(orders).getByRole('button', { name: '항복 권고 — 순 고르기' }));
    expect(onSurrender).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('region', { name: '항복 권고' })).toHaveTextContent('지금은 거절할 것으로 보입니다.');
    expect(within(screen.getByRole('region', { name: '함락되면' })).getAllByRole('listitem')).toHaveLength(4);
    expect(document.body).not.toHaveTextContent('국고');

    const [ended] = siegeRows([siege({ status: 'FALLEN' })]);
    rerender(<SiegeOrders row={ended} assault={null} surrender={null} onAssault={() => {}} onSurrender={() => {}} />);
    expect(screen.queryByRole('region', { name: '명령' })).toBeNull();
});

test('보루 명령 — 불가면 숨기지 않고 점선 + 사유', () => {
    render(<FortOrders fort={fortRows([fort])[0]} availability={{ inputId: 'action.siegeRoadFort', status: 'BLOCKED', reason: '보루에 닿은 군단이 없습니다.' }} onBesiege={() => {}} />);
    expect(screen.getByRole('button', { name: /보루 포위/ })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('region', { name: '도로 보루' })).toHaveTextContent('보루에 닿은 군단이 없습니다.');
});
