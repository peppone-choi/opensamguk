import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { CAPITAL_NOTE, CutPanel, TransportPanel, UpkeepWaiting, WarehouseTable } from '../components/territory/SupplyParts';
import type { Warehouses } from '../lib/campaign-reads';
import { connectedTotal, cutRows, stockLine, warehouseRows } from '../lib/supply-view';

const stock = (money: number, grain = 0) => ({ money, grain, iron: 0, timber: 0, horses: 0 });
const data: Warehouses = {
    status: 'READY',
    invalidCount: 1,
    warehouses: [
        { cityId: 5, name: '양적현', commanderyName: '영천군', isCapital: false, supplied: true, stock: stock(300, 40) },
        { cityId: 3, name: '허현', commanderyName: '영천군', isCapital: true, supplied: true, stock: stock(1200, 800) },
        { cityId: 9, name: '윤씨현', commanderyName: '영천군', isCapital: false, supplied: false, stock: stock(50) },
    ],
};
const rows = warehouseRows(data);

test('보기 모델 — 수도 먼저, 합계는 본망만, 끊긴 곳, 5자원 한 줄', () => {
    expect(rows.map((r) => [r.name, r.kind, r.connected])).toEqual([['허현', 'capital', true], ['양적현', 'county', true], ['윤씨현', 'county', false]]);
    expect(connectedTotal(rows)).toEqual({ money: 1500, grain: 840, iron: 0, timber: 0, horses: 0 });
    expect(connectedTotal(rows.filter((r) => !r.connected))).toBeNull();
    expect(cutRows(rows).map((r) => r.cityId)).toEqual([9]);
    expect(stockLine(stock(1200, 800))).toBe('금 1,200 · 쌀 800 · 철 0 · 목재 0 · 말 0');
});

test('재고 표 — 구분 칩 · 망 · 합계(본망) · 읽지 못한 창고 경고 · 은퇴어 없는 안내', () => {
    render(<WarehouseTable rows={rows} total={connectedTotal(rows)} invalidCount={data.invalidCount} />);
    const trs = screen.getAllByRole('row');
    expect(trs[1]).toHaveTextContent('허현수도1,200800');
    expect(trs[3]).toHaveTextContent('끊김');
    expect(trs[4]).toHaveTextContent('합계(본망)1,500840');
    expect(screen.getByRole('alert')).toHaveTextContent('읽지 못한 창고 1곳');
    expect(screen.getByText(CAPITAL_NOTE)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('국고');
});

test('모바일 창고 카드 — 본망 합계 카드와 끊김 칩', () => {
    render(<WarehouseTable rows={rows} total={connectedTotal(rows)} mobile />);
    expect(screen.getByTestId('connected-total')).toHaveTextContent('금 1,500 · 쌀 840');
    const cards = within(screen.getByRole('list', { name: '창고' })).getAllByRole('listitem');
    expect(cards[2]).toHaveTextContent('끊김');
});

test('끊긴 곳 — 까닭은 준비 중 · 지도 고리, 없으면 「모든 창고가 수도와 이어져 있습니다」', () => {
    const { rerender } = render(<CutPanel rows={rows} mapHref={(id) => `/game/pep?layer=supply&focus=${id}`} />);
    const [cut] = within(screen.getByRole('list', { name: '끊긴 곳' })).getAllByRole('listitem');
    expect(cut).toHaveTextContent('윤씨현은 수도와 끊겨 제 창고만 씁니다.');
    expect(cut.querySelector('[data-waiting="cut-reason"]')).toHaveTextContent('준비 중');
    expect(within(cut).getByRole('link')).toHaveAttribute('href', '/game/pep?layer=supply&focus=9');
    rerender(<CutPanel rows={rows.filter((r) => r.connected)} />);
    expect(screen.getByRole('status')).toHaveTextContent('모든 창고가 수도와 이어져 있습니다.');
});

test('녹봉 전망은 서버 대기 · 물자조달은 InputAction(가능하면 누른다), 호위 수송은 규칙 없음 칩', () => {
    render(<UpkeepWaiting />);
    expect(screen.getByText('지급 전망 — 준비 중')).toBeInTheDocument();
    const onTransport = vi.fn();
    render(<TransportPanel generalName="하후돈" availability={{ inputId: 'action.transport', status: 'AVAILABLE' }} onTransport={onTransport} />);
    fireEvent.click(screen.getByRole('button', { name: '물자조달 — 명령 목록에 넣기' }));
    expect(onTransport).toHaveBeenCalledTimes(1);
    expect(screen.getByText('호위 · 지연 수송 — 규칙 없음')).toBeInTheDocument();
});
