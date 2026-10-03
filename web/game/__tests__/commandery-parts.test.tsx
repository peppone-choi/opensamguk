import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { CommanderyPolicyCard, CommanderySummaryCard, CommanderyTable, INDICATOR_WAITING } from '../components/commandery/CommanderyParts';
import type { Policies, Warehouses, Works } from '../lib/campaign-reads';
import { commanderyRows, commanderySummary, sortCommandery } from '../lib/commandery-view';
import type { CountyDirectory } from '../lib/directory-reads';

// 도움말 고리(HelpedInputAction)는 도움말 서랍 없이도 그린다 — 여기서는 단추 · 사유만 본다.
vi.mock('../components/campaign/HelpedInputAction', async () => {
    const { InputAction } = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
    return { HelpedInputAction: InputAction };
});

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const directory: CountyDirectory = {
    status: 'READY', scope: 'COMMANDERY', commandery: { id: 'c-yc', name: '영천군' }, period: 'GAME_MONTH', basis: 'CURRENT_STATE_FORECAST', stamp: null,
    counties: [
        { cityId: 129, name: '양성현', commanderyId: 'c-yc', visibility: 'FULL', income: { money: 120, grain: 80 } },
        { cityId: 130, name: '허현', commanderyId: 'c-yc', visibility: 'FULL', income: { money: 300, grain: 40 } },
        { cityId: 131, name: '영음현', commanderyId: 'c-yc', visibility: 'INTEL', income: null },
    ],
};
const policies: Policies = {
    status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, corps: [],
    counties: [
        { countyId: 129, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: { policy: 'FARM', label: '농업', source: 'DEFAULT' },
            seat: null, settable: true, blocked: null },
        { countyId: 130, name: '허현', commanderyName: '영천군', active: { policy: 'TRADE', label: '상업' }, pending: null,
            effective: { policy: 'TRADE', label: '상업', source: 'COUNTY' }, seat: { generalId: 102, name: '이전', placed: true }, settable: true, blocked: null },
    ],
    commanderies: [{ commanderyId: 'c-yc', name: '영천군', countyIds: [129, 130, 131], active: { policy: 'FARM', label: '농업' }, pending: null, settable: true, blocked: null }],
};
const works: Works = { status: 'READY', counties: [
    { countyId: 129, provinceId: null, provinceIds: [], name: '양성현', commanderyName: '영천군', warehouse: null, completed: [], startable: [],
        active: { work: 'FARM', label: '둔전', percent: 50, remainingPhases: 2, remainingCost: zero, stopReasonText: null,
            stopReason: null, startsAtNextBoundary: false } },
    { countyId: 130, provinceId: null, provinceIds: [], name: '허현', commanderyName: '영천군', warehouse: null, completed: [], startable: [],
        active: { work: 'WAREHOUSE', label: '창고', percent: 30, remainingPhases: 4, remainingCost: zero, stopReasonText: '창고의 자재가 모자랍니다.',
            stopReason: 'INSUFFICIENT_STOCK', startsAtNextBoundary: false } },
] };
const warehouses: Warehouses = { status: 'READY', warehouses: [
    { cityId: 129, name: '양성현', commanderyName: '영천군', isCapital: false, supplied: false, stock: zero },
] };

test('보기 모델 — 현 목록에 방침 · 공사 · 창고를 잇고, 경고는 서버 값에서 곧장 읽히는 셋만', () => {
    const rows = commanderyRows(directory, policies, works, warehouses);
    expect(rows.map((r) => [r.name, r.magistrate, r.policy, r.income, r.work, r.warnings])).toEqual([
        ['양성현', '빈자리', '농업', '월 금 120 · 쌀 80', '둔전 50%', ['NO_MAGISTRATE', 'ISOLATED']],
        ['허현', '이전', '상업', '월 금 300 · 쌀 40', '창고 30%', ['MATERIAL_SHORT']],
        ['영음현', null, null, null, null, []],
    ]);
    expect(sortCommandery(rows, 'money').map((r) => r.name)).toEqual(['허현', '양성현', '영음현']);
    expect(sortCommandery(rows, 'warnings').map((r) => r.name)).toEqual(['양성현', '허현', '영음현']);
    expect(commanderySummary(rows)).toEqual({ total: 3, noMagistrate: 1, isolated: 1, materialShort: 1 });
});

test('보조 조회를 못 읽으면 그 칸은 「?」, 그 경고 수는 null — 「없음」 · 0 으로 세지 않는다(#1274 리뷰)', () => {
    const rows = commanderyRows(directory, null, null, warehouses);
    expect(rows.map((r) => [r.name, r.magistrate, r.policy, r.work, r.warnings])).toEqual([
        ['양성현', '?', '?', '?', ['ISOLATED']],
        ['허현', '?', '?', '?', []],
        ['영음현', '?', '?', '?', []],
    ]);
    expect(commanderySummary(rows, { policies: false, works: false, warehouses: true })).toEqual({ total: 3, noMagistrate: null, isolated: 1, materialShort: null });
    render(<CommanderySummaryCard summary={{ total: 3, noMagistrate: null, isolated: 1, materialShort: null }}
        sources={{ policies: 'error', works: 'loading', warehouses: 'ready' }} />);
    const list = screen.getByRole('list', { name: '군 요약' });
    expect(list).toHaveTextContent('빈 현령 ?곳 — 불러오지 못했습니다');
    expect(list).toHaveTextContent('자재 부족 ?곳 — 불러오는 중');
    expect(list).toHaveTextContent('고립 1곳');
    expect(list).not.toHaveTextContent('빈 현령 0곳');
});

test('표 — 현 이름은 현 상세 고리, 경고 칩, 7지표는 준비 중 한 줄, 정렬 Seg', () => {
    const onSortChange = vi.fn();
    render(<CommanderyTable rows={commanderyRows(directory, policies, works, warehouses)} sort="name" onSortChange={onSortChange}
        countyHref={(id) => `/game/pep/territory/county/${id}`} />);
    expect(screen.getByRole('link', { name: '양성현' })).toHaveAttribute('href', '/game/pep/territory/county/129');
    const trs = screen.getAllByRole('row');
    expect(trs[1]).toHaveTextContent('빈 현령');
    expect(trs[1]).toHaveTextContent('고립');
    expect(trs[3]).toHaveTextContent('첩보');
    expect(screen.getByText(INDICATOR_WAITING)).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '정렬' })).getByRole('radio', { name: '경고 수' }));
    expect(onSortChange).toHaveBeenCalledWith('warnings');
});

test('요약 · 군 방침 — 민심 위험 · 적 군단은 준비 중, 군주가 아니면 서버 사유로 점선', () => {
    render(<CommanderySummaryCard summary={{ total: 3, noMagistrate: 1, isolated: 2, materialShort: 0 }} />);
    const items = within(screen.getByRole('list', { name: '군 요약' })).getAllByRole('listitem');
    expect(items.map((i) => i.textContent)).toEqual(['빈 현령 1곳', '고립 2곳', '자재 부족 0곳', '민심 위험 · 적 군단 준비 중']);
    render(<CommanderyPolicyCard row={null} availability={{ inputId: 'policy.set', status: 'BLOCKED', reason: '군 방침은 군주만 정합니다.' }} onChange={() => {}} />);
    expect(screen.getByRole('button', { name: /군 방침 바꾸기/ })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('region', { name: '군 방침' })).toHaveTextContent('군 방침은 군주만 정합니다.');
});
