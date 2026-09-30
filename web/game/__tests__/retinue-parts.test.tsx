import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { BondPanel, bondGroups } from '../components/retinue/BondPanel';
import { PersonDetail } from '../components/retinue/PersonDetail';
import { RenownBand } from '../components/retinue/RenownBand';
import { RetinueList } from '../components/retinue/RetinueList';
import { UnitCards } from '../components/retinue/UnitCards';
import type { PersonCard, Retinue } from '../lib/campaign-reads';
import { renownBand, retinueRows, unitRows } from '../lib/retinue-view';

const person = (id: number, over: Partial<PersonCard> = {}): PersonCard => ({
    retainerId: id, generalId: 100 + id, name: `인물${id}`, picture: null, imageServer: 0, loyalty: 60,
    roleLabel: '참모', taskLabel: '없음', stats: null, cost: null, aptitudes: null, bonds: [], departureOrder: null, locationCityId: null, ...over,
});
const hyangdang = { kind: 'HYANGDANG', label: '향당', nativeCountyName: '패국 초현', sameAsLord: true };
const retinue: Retinue = {
    status: 'READY', renown: 30, costSum: 36, overCapacity: true,
    people: [
        person(1, { name: '허저', loyalty: 90, cost: 12, bonds: [hyangdang] }),
        person(2, { name: '무명 공조', loyalty: 40, cost: 13, departureOrder: 1, generalId: null }),
    ],
    units: [
        { id: 10, name: '하후돈 부곡 1', troops: 300, crewTypeId: 1, crewTypeName: '보병', training: 50, morale: 60, fatigue: 5, provisions: 0, provisionMonths: 2, commanderRetainerId: 1 },
        { id: 11, name: '하후돈 부곡 2', troops: 200, crewTypeId: 2, crewTypeName: '궁병', training: 40, morale: 55, fatigue: 0, provisions: 0, provisionMonths: 1, commanderRetainerId: null },
    ],
};
const rows = retinueRows(retinue, null);

test('인물 목록 — 자리는 배치 원장 값(없으면 미배치), roleLabel 은 보이지 않는다', () => {
    render(<RetinueList rows={rows} sort="registered" onSortChange={() => {}} selectedId={1} onSelect={() => {}} />);
    const list = screen.getByRole('listbox', { name: '부의 인물' });
    const [first, second] = within(list).getAllByRole('option');
    expect(first).toHaveAttribute('aria-selected', 'true');
    expect(first).toHaveTextContent('향당 · 패국 초현');
    expect(first).toHaveTextContent('자리 미배치');
    expect(second).toHaveTextContent('이탈 1순위');
    expect(second).toHaveTextContent('결속 없음');
    expect(list).not.toHaveTextContent('참모');
});

test('정렬은 라디오 넷 — 누르면 알린다, 모바일 목록은 고름 표시가 없다', () => {
    const onSortChange = vi.fn();
    const onSelect = vi.fn();
    render(<RetinueList rows={rows} sort="registered" onSortChange={onSortChange} selectedId={1} onSelect={onSelect} mobile />);
    const radios = within(screen.getByRole('radiogroup', { name: '정렬' })).getAllByRole('radio');
    expect(radios.map((r) => r.textContent)).toEqual(['등록순', '코스트', '충성', '이탈 판정']);
    fireEvent.click(radios[2]);
    expect(onSortChange).toHaveBeenCalledWith('loyalty');
    const options = screen.getAllByRole('option');
    expect(options.every((o) => o.getAttribute('aria-selected') === 'false')).toBe(true);
    fireEvent.click(options[1]);
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ retainerId: 2 }));
});

test('인물 상세 — 배치 행이 없으면 단추를 그리지 않고, BLOCKED 면 서버 사유를 보인다', () => {
    const { rerender } = render(<PersonDetail row={rows[0]} assign={null} onAssign={() => {}} />);
    expect(screen.queryByRole('button', { name: /자리에 배치/ })).toBeNull();
    expect(screen.getByText('주공과 같은 고향')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: '능력' })).toHaveTextContent('통솔—');

    rerender(<PersonDetail row={rows[0]} assign={{ inputId: 'placement.assign', status: 'BLOCKED', code: 'CARD_DEPLOYED', reason: '출전 중인 카드입니다.' }} onAssign={() => {}} />);
    const blocked = screen.getByRole('button', { name: /자리에 배치/ });
    expect(blocked).toHaveAttribute('aria-disabled', 'true');
    expect(blocked).toHaveAttribute('data-input-id', 'placement.assign');
    expect(screen.getAllByText('출전 중인 카드입니다.').length).toBeGreaterThan(0);

    const onAssign = vi.fn();
    rerender(<PersonDetail row={rows[0]} assign={{ inputId: 'placement.assign', status: 'AVAILABLE' }} onAssign={onAssign} />);
    fireEvent.click(screen.getByRole('button', { name: '자리에 배치' }));
    expect(onAssign).toHaveBeenCalledTimes(1);
});

test('사람 장수 구분 — 서버가 안 주면 「준비 중」 한 줄, 사람이면 배치 대신 조정 발령 고리', () => {
    const avail = { inputId: 'placement.assign', status: 'AVAILABLE' } as const;
    const { rerender, container } = render(<PersonDetail row={rows[0]} assign={avail} onAssign={() => {}} />);
    expect(container.querySelector('[data-waiting="human-flag"]')).toHaveTextContent('준비 중');
    expect(screen.getByRole('button', { name: '자리에 배치' })).toBeInTheDocument();

    rerender(<PersonDetail row={rows[0]} assign={avail} onAssign={() => {}} isHuman dispatchHref="/game/pep/court?dispatch=101" />);
    expect(container.querySelector('[data-waiting="human-flag"]')).toBeNull();
    expect(screen.queryByRole('button', { name: '자리에 배치' })).toBeNull();
    expect(screen.getByRole('link', { name: '발령은 조정에서 →' })).toHaveAttribute('href', '/game/pep/court?dispatch=101');

    // K4-18: null = 서버가 인물을 못 풀었다(NPC 확정 아님) — 필드 없음과 같이 「준비 중」, 배치 단추는 서버 사유 그대로.
    rerender(<PersonDetail row={rows[0]} assign={avail} onAssign={() => {}} isHuman={null} />);
    expect(container.querySelector('[data-waiting="human-flag"]')).toHaveTextContent('준비 중');
    expect(screen.getByRole('button', { name: '자리에 배치' })).toBeInTheDocument();

    rerender(<PersonDetail row={rows[0]} assign={avail} onAssign={() => {}} isHuman={false} />);
    expect(container.querySelector('[data-waiting="human-flag"]')).toBeNull();
    expect(screen.getByRole('button', { name: '자리에 배치' })).toBeInTheDocument();
});

test('찾기 · 거르기 줄은 목록이 칸을 넘칠 때만, 걸어 둔 동안은 계속 보인다', () => {
    const search = { query: '', onQueryChange: () => {}, filter: 'all' as const, onFilterChange: () => {}, total: 2 };
    const { rerender } = render(<RetinueList rows={rows} sort="registered" onSortChange={() => {}} onSelect={() => {}} search={search} />);
    // jsdom 은 크기가 0 — 넘치지 않는다.
    expect(screen.queryByRole('searchbox', { name: '이름 찾기' })).toBeNull();

    rerender(<RetinueList rows={rows.slice(0, 1)} sort="registered" onSortChange={() => {}} onSelect={() => {}} search={{ ...search, filter: 'risk' }} />);
    expect(screen.getByRole('searchbox', { name: '이름 찾기' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('2명 중 1');
});

test('찾기 줄 — 칸이 넘치면 보인다', () => {
    const sh = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(900);
    const ch = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(400);
    try {
        const search = { query: '', onQueryChange: () => {}, filter: 'all' as const, onFilterChange: () => {}, total: 2 };
        render(<RetinueList rows={rows} sort="registered" onSortChange={() => {}} onSelect={() => {}} search={search} />);
        expect(screen.getByRole('searchbox', { name: '이름 찾기' })).toBeInTheDocument();
    } finally {
        sh.mockRestore();
        ch.mockRestore();
    }
});

test('부대 카드 — 지휘 없는 부대는 움직일 수 없다고 적는다, 쌀은 달 분', () => {
    render(<UnitCards units={unitRows(retinue)} />);
    const [a, b] = screen.getAllByRole('listitem');
    expect(a).toHaveTextContent('지휘 허저');
    expect(a).toHaveTextContent('쌀 2달 분');
    expect(b).toHaveTextContent('지휘 없음 — 움직일 수 없음');
});

test('명망 띠 — 초과면 경고, 인물 0이면 경고 없이 「인물 0」, 값이 없으면 짓지 않는다', () => {
    const band = renownBand(retinue, null);
    const { rerender } = render(<RenownBand band={band} people={2} units={2} yuedanHref="/game/pep/retinue/yuedan" />);
    expect(screen.getByTestId('renown-band')).toHaveTextContent('상한 초과 — 다음 월단평에 충성 낮은 인물부터 이탈 판정');
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuetext', '36 / 30');
    expect(screen.getByRole('link', { name: '월단평 →' })).toHaveAttribute('href', '/game/pep/retinue/yuedan');

    rerender(<RenownBand band={{ renown: 30, costSum: 0, overCapacity: false, ratio: 0 }} people={0} units={0} compact />);
    expect(screen.getByTestId('renown-band')).toHaveTextContent('인물 0');
    expect(screen.getByTestId('renown-band')).not.toHaveTextContent('상한');

    rerender(<RenownBand band={renownBand(null, null)} people={1} units={0} />);
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuetext', '— / —');
    expect(screen.getByTestId('renown-band')).not.toHaveTextContent('상한 안');
});

test('결속 칸 — 같은 결속끼리 묶고 주공 고향이면 주공 이름을 앞에, 나머지 종류는 준비 중', () => {
    expect(bondGroups(rows)).toEqual([{ text: '향당 · 패국 초현', members: ['허저'], withLord: true }]);
    render(<BondPanel rows={rows} lordName="하후돈" />);
    const [first, rest] = screen.getAllByRole('listitem');
    expect(first).toHaveTextContent('하후돈 · 허저');
    expect(rest).toHaveTextContent('준비 중');
});
