import { fireEvent, render, screen } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { UNOWNED_NATION_NAME } from '@opensamguk/ui';
import { MyLocationCard, SelectedCountyCard } from '../components/warroom/MapCards';
import { countyNation } from '../lib/county-view';

test('선택 카드 — 무주 · 첩보 n순 전 · 고립 · 특산, 첩보 막힘은 서버 사유(숨기지 않음), 세 단추 · 닫기', () => {
    const onClose = vi.fn();
    render(<SelectedCountyCard name="진류현" commanderyName="진류군" nation={countyNation(null)} visibility="INTEL" intelAge={3} isolated
        specialties={['철 월 12']} detailHref="/game/pep/territory/county/41"
        scout={{ inputId: 'action.scout', status: 'BLOCKED', reason: '첩보할 인물이 없습니다.' }} onScout={() => {}}
        commandHref="/game/pep?target=county:41" onClose={onClose} />);
    const card = screen.getByRole('region', { name: '진류현 선택' });
    expect(card).toHaveTextContent(`진류군${UNOWNED_NATION_NAME}첩보 · 3순 전고립`);
    expect(card).toHaveTextContent('철 월 12');
    expect(screen.getByRole('button', { name: /첩보/ })).toHaveAttribute('aria-disabled', 'true');
    expect(card).toHaveTextContent('첩보할 인물이 없습니다.');
    expect(screen.getByRole('link', { name: '현 상세' })).toHaveAttribute('href', '/game/pep/territory/county/41');
    expect(screen.getByRole('link', { name: '여기로 명령' })).toHaveAttribute('href', '/game/pep?target=county:41');
    fireEvent.click(screen.getByRole('button', { name: '선택 카드 닫기' }));
    expect(onClose).toHaveBeenCalled();
});

test('내 위치 카드 — 성 안이면 「지금 여기」, 성 밖이면 경고 · 현 상세 없음', () => {
    const { rerender } = render(<MyLocationCard cityName="양적현" detailHref="/game/pep/territory/county/2" commandHref="/game/pep?target=county:2" onClose={() => {}} />);
    expect(screen.getByText('지금 여기')).toBeInTheDocument();
    rerender(<MyLocationCard cityName={null} detailHref={null} commandHref="/game/pep?target=here" onClose={() => {}} />);
    expect(screen.getByRole('heading', { name: '성 밖' })).toBeInTheDocument();
    expect(screen.getByText(/도시 행동을 할 수 없습니다/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '현 상세' })).toBeNull();
});
