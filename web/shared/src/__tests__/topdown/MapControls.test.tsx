import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { LegendSwatch, MapLayerButtons, MapViewBar } from '../../map/topdown/MapControls';
import { DEFAULT_LAYERS } from '../../map/topdown/renderer';
import type { TopdownMapHandle } from '../../map/topdown/TopdownMap';

// 보드 MapViewBar(왼쪽 아래: 주 · 군 · 현 → + · − → 내 위치로) · 오른쪽 위 「지도 레이어」 · 「범례」.
const handle = (): TopdownMapHandle & { setLevel: ReturnType<typeof vi.fn>; zoomStep: ReturnType<typeof vi.fn> } => ({
  setLevel: vi.fn(),
  zoomStep: vi.fn(),
  centerOn: vi.fn(),
  focusCity: vi.fn(() => true),
});

describe('MapViewBar', () => {
  it('보기 수준은 라디오 셋, 지금 수준만 골라져 있고 Tab 순서에 든다', () => {
    render(<MapViewBar handle={handle()} level="commandery" />);
    const group = screen.getByRole('radiogroup', { name: '보기 수준' });
    expect(group).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '군 보기' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: '주 보기' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('radio', { name: '군 보기' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('radio', { name: '현 보기' })).toHaveAttribute('tabindex', '-1');
  });

  it('누르면 그 수준으로, 화살표는 다음 칸으로 가고 지도 이동으로 새지 않는다', () => {
    const h = handle();
    const outer = vi.fn();
    render(<div onKeyDown={outer}><MapViewBar handle={h} level="ju" /></div>);
    fireEvent.click(screen.getByRole('radio', { name: '현 보기' }));
    expect(h.setLevel).toHaveBeenLastCalledWith('county');
    fireEvent.keyDown(screen.getByRole('radio', { name: '주 보기' }), { key: 'ArrowDown' });
    expect(h.setLevel).toHaveBeenLastCalledWith('commandery');
    fireEvent.keyDown(screen.getByRole('radio', { name: '주 보기' }), { key: 'ArrowUp' });
    expect(h.setLevel).toHaveBeenLastCalledWith('county');
    expect(outer).not.toHaveBeenCalled();
  });

  it('+ · − 는 한 멈춤 자리씩', () => {
    const h = handle();
    render(<MapViewBar handle={h} level="county" />);
    fireEvent.click(screen.getByRole('button', { name: '확대' }));
    fireEvent.click(screen.getByRole('button', { name: '축소' }));
    expect(h.zoomStep.mock.calls).toEqual([[1], [-1]]);
  });

  it('누르는 곳은 모두 44 이상(인라인 최소 크기)', () => {
    const { container } = render(<MapViewBar handle={handle()} level="county" onMyLocation={() => undefined} />);
    for (const button of container.querySelectorAll('button')) {
      expect(button.style.minWidth).toBe('44px');
      expect(button.style.minHeight).toBe('44px');
    }
  });

  it('내 장수 자리를 모르면 「내 위치로」는 숨기지 않고 끈 채 사유를 단다', () => {
    const { rerender } = render(<MapViewBar handle={handle()} level="county" />);
    const button = screen.getByRole('button', { name: '내 위치로(Home)' });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveAccessibleDescription('내 장수 자리를 아직 모릅니다');
    const go = vi.fn();
    rerender(<MapViewBar handle={handle()} level="county" onMyLocation={go} />);
    fireEvent.click(screen.getByRole('button', { name: '내 위치로(Home)' }));
    expect(go).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '내 위치로(Home)' })).not.toHaveAttribute('aria-disabled');
  });
});

describe('MapLayerButtons', () => {
  const legend = <><LegendSwatch color="#4a6fa5" label="조조" /><LegendSwatch label="미정찰" hatched /></>;

  it('지도 레이어 판: 켜고 끄면 그 층만 뒤집고, 서버 칸이 없는 층은 「서버 대기 · 계약판 행」으로 보인다', () => {
    const change = vi.fn();
    render(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={change} legend={legend}
      pending={[{ id: 'fog', label: '시야', contract: 'K2-08' }]} />);
    const open = screen.getByRole('button', { name: '지도 레이어' });
    expect(open).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(open);
    expect(open).toHaveAttribute('aria-expanded', 'true');
    const panel = screen.getByRole('region', { name: '지도 레이어' });
    const names = screen.getByRole('button', { name: /도시 이름/ });
    expect(names).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(names);
    expect(change).toHaveBeenLastCalledWith({ ...DEFAULT_LAYERS, cityNames: false });
    fireEvent.click(screen.getByRole('button', { name: /구역 경계/ }));
    expect(change).toHaveBeenLastCalledWith({ ...DEFAULT_LAYERS, provinceLines: true });
    expect(panel).toHaveTextContent('시야서버 대기 · K2-08');
  });

  it('범례와 레이어는 한 번에 하나만 열리고, Esc 로 닫힌다', () => {
    render(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={() => undefined} legend={legend} />);
    fireEvent.click(screen.getByRole('button', { name: '지도 레이어' }));
    fireEvent.click(screen.getByRole('button', { name: '범례' }));
    expect(screen.queryByRole('region', { name: '지도 레이어' })).toBeNull();
    expect(screen.getByRole('region', { name: '범례' })).toHaveTextContent('조조');
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('region', { name: '범례' })).toBeNull();
  });

  it('좁은 화면(compact)은 글자 없는 단추라 이름은 aria-label 로 읽힌다', () => {
    render(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={() => undefined} legend={legend} compact />);
    expect(screen.getByRole('button', { name: '지도 레이어' })).toHaveTextContent('');
    expect(screen.getByRole('button', { name: '범례' })).toBeInTheDocument();
  });
});
