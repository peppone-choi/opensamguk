import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { DepartureOrder, MyRenown, Ranking, RenownPaths } from '../components/yuedan/YuedanParts';
import type { YuedanRow } from '../lib/campaign-reads';

const row = (rank: number, generalId: number, name: string, over: Partial<YuedanRow> = {}): YuedanRow => ({
    rank, generalId, name, nationId: 1, nationName: '조조', nationColor: '#4f7fbf', renown: 100 - rank, ...over,
});
const ranking = [
    row(1, 1, '조조', { reasons: [{ kind: 'DOMESTIC_MERIT', label: '치적', count: 2, amount: 3 }] }),
    row(2, 9, '석도', { nationId: 0, nationName: null, nationColor: null }),
    row(3, 7, '하후돈', { reasons: [{ kind: 'DISPATCH_REFUSAL', label: '발령 거절', count: 1, amount: -2 }] }),
];

test('순위 표 — 내 줄 청동 · 재야 글자 · 사유 칩, 「내 순위로」는 내 줄로 초점을 옮긴다', () => {
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    render(<Ranking ranking={ranking} meId={7} />);
    const rows = screen.getAllByRole('row');
    const me = rows[3];
    expect(me).toHaveAttribute('data-me', 'true');
    expect(me).toHaveTextContent('발령 거절 -2');
    expect(rows[1]).toHaveTextContent('치적 +3 ×2');
    expect(rows[2]).toHaveTextContent('재야');
    fireEvent.click(screen.getByRole('button', { name: '내 순위로' }));
    expect(scroll).toHaveBeenCalled();
    expect(me).toHaveFocus();
});

test('순위 — 내 줄이 없으면 「내 순위로」도 없다(모바일 카드)', () => {
    render(<Ranking ranking={ranking.slice(0, 2)} meId={7} mobile />);
    expect(screen.queryByRole('button', { name: '내 순위로' })).toBeNull();
    expect(within(screen.getByRole('list', { name: '월단평 순위' })).getAllByRole('listitem')).toHaveLength(2);
});

test('경로 — 서버 라벨 넷 · 넷', () => {
    render(<RenownPaths />);
    expect(screen.getByText('오르는 경로').parentElement).toHaveTextContent('전공치적관직결속');
    expect(screen.getByText('떨어지는 경로').parentElement).toHaveTextContent('패전배신실정발령 거절');
});

test('내 명망 — 초과 칩 · 막대 · 대기 사건(없으면 「아직 없습니다」)', () => {
    const { rerender } = render(
        <MyRenown self={{ generalId: 7, renown: 30, retinueCost: 36, overCapacity: true }}
            pending={[{ kind: 'DOMESTIC_MERIT', label: '치적', stamp: '0200-04', sourceLabel: '직접 내정 행동', amount: 2 }]} />,
    );
    const card = screen.getByTestId('my-renown');
    expect(card).toHaveTextContent('코스트 초과 — 이탈 판정 대상');
    expect(card).toHaveTextContent('36 / 30');
    expect(card).toHaveTextContent('치적 · 직접 내정 행동 +2');
    rerender(<MyRenown self={{ generalId: 7, renown: null, retinueCost: null, overCapacity: false }} pending={undefined} compact />);
    expect(screen.getByTestId('my-renown')).toHaveTextContent('— / —');
    expect(screen.getByTestId('my-renown')).toHaveTextContent('아직 없습니다');
    expect(screen.getByTestId('my-renown')).not.toHaveTextContent('코스트 초과');
});

test('이탈 판정 순서 — 상한 안이면 「이탈 판정 없음」, 넘으면 순서 목록', () => {
    const d = { order: 1, retainerId: 3, name: '무명 공조', picture: null, imageServer: 0, loyalty: 40, cost: 13 };
    const { rerender } = render(<DepartureOrder rows={[d]} overCapacity={false} retinueHref="/game/pep/retinue" />);
    expect(screen.getByRole('status')).toHaveTextContent('상한 안 — 이탈 판정 없음');
    expect(screen.getByRole('link', { name: '부 편성으로 →' })).toHaveAttribute('href', '/game/pep/retinue');
    rerender(<DepartureOrder rows={[d]} overCapacity />);
    const items = within(screen.getByRole('list', { name: '이탈 판정 순서' })).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('무명 공조');
    expect(items[0]).toHaveTextContent('충성 40');
});
