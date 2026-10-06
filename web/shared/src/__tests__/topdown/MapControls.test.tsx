import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LegendLine, LegendSwatch, MAP_LAYERS_STORAGE_KEY, MapLayerButtons, MapViewBar, parseStoredLayers, useStoredMapLayers } from '../../map/topdown/MapControls';
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
    expect(screen.getByRole('button', { name: '내 위치로(Home)' })).not.toHaveClass('os-button--disabled');
    expect(screen.getByRole('button', { name: '내 위치로(Home)' })).not.toHaveAttribute('aria-describedby');
    expect(screen.queryByRole('tooltip')).toBeNull();
  });

  it.each([
    [undefined, '내 장수 자리를 아직 모릅니다'],
    ['장소 표를 불러오는 중입니다', '장소 표를 불러오는 중입니다'],
  ])('막힌 「내 위치로」를 누르면 사유가 보이고 다시 누르면 닫힌다(%s)', (reason, expected) => {
    render(<MapViewBar handle={handle()} level="county" myLocationReason={reason} />);
    const button = screen.getByRole('button', { name: '내 위치로(Home)' });
    expect(button).toHaveAccessibleDescription(expected);
    expect(screen.queryByRole('tooltip')).toBeNull();
    fireEvent.click(button);
    expect(screen.getByRole('tooltip')).toBeVisible();
    expect(screen.getByRole('tooltip')).toHaveTextContent(expected);
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toBeDisabled();
    expect(button).toHaveClass('os-button--disabled');
    expect(button.style.minWidth).toBe('44px');
    expect(button.style.minHeight).toBe('44px');
    fireEvent.click(button);
    expect(screen.queryByRole('tooltip')).toBeNull();
  });
});

describe('LegendSwatch', () => {
  it('색은 세력색(#rrggbb) 또는 토큰 var(--…)만 그대로 — 그 밖은 기본색(원장 D90)', () => {
    const { container } = render(<><LegendSwatch color="#4a6fa5" label="갑" /><LegendSwatch color="var(--muted)" label="무주" /><LegendSwatch color="url(x)" label="을" /></>);
    const fills = Array.from(container.querySelectorAll<HTMLElement>('i[aria-hidden="true"]')).map((i) => i.style.background);
    expect(fills).toEqual(['rgb(74, 111, 165)', 'var(--muted)', 'rgb(142, 136, 121)']);
  });
});

describe('MapLayerButtons', () => {
  const legend = <><LegendSwatch color="#4a6fa5" label="조조" /><LegendSwatch label="미정찰" hatched /></>;

  it('지도 레이어 판: 켜고 끄면 그 층만 뒤집고, 서버 칸이 없는 층은 「서버 대기 · 계약판 행」으로 보인다', () => {
    const change = vi.fn();
    render(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={change} legend={legend}
      pending={[{ id: 'supply', label: '보급선', contract: 'K2-09' }]} />);
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
    expect(panel).toHaveTextContent('보급선서버 대기 · K2-09');
    // 서버 대기 줄과 같은 이름의 층은 켜고 끄는 줄을 숨긴다
    expect(screen.queryByRole('button', { name: /보급선/ })).toBeNull();
    // 시야는 서버 郡 시야로 칠하는 진짜 층이다(기본 켬) — 「서버 대기」 줄이 아니다
    const fog = screen.getByRole('button', { name: /시야/ });
    expect(fog).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(fog);
    expect(change).toHaveBeenLastCalledWith({ ...DEFAULT_LAYERS, fog: false });
  });

  it('서버가 칸을 주면(대기 줄 없음) 보급선도 켜고 끄는 층, 대기 줄 글은 note 로 바꿀 수 있다', () => {
    const change = vi.fn();
    const { unmount } = render(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={change} legend={legend} />);
    fireEvent.click(screen.getByRole('button', { name: '지도 레이어' }));
    const supply = screen.getByRole('button', { name: /보급선/ });
    expect(supply).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(supply);
    expect(change).toHaveBeenLastCalledWith({ ...DEFAULT_LAYERS, supply: false });
    unmount();
    render(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={change} legend={legend}
      pending={[{ id: 'supply', label: '보급선', contract: 'K4-06', note: '불러오지 못함' }]} />);
    fireEvent.click(screen.getByRole('button', { name: '지도 레이어' }));
    expect(screen.getByRole('region', { name: '지도 레이어' })).toHaveTextContent('보급선불러오지 못함');
  });

  it('범례 선: 토큰 색 그대로, 끊김은 점선 + ×, 토큰이 아닌 색은 기본색', () => {
    const { container } = render(<><LegendLine color="var(--moss-2)" label="보급 연결" /><LegendLine color="var(--rust-2)" label="보급 끊김" cut /><LegendLine color="#ff0000" label="엉뚱" /></>);
    const paths = [...container.querySelectorAll('path')];
    expect(paths[0]).toHaveAttribute('stroke', 'var(--moss-2)');
    expect(paths[0]).not.toHaveAttribute('stroke-dasharray');
    expect(paths[1]).toHaveAttribute('stroke-dasharray', '5 4');
    expect(paths[2]).toHaveAttribute('d', 'M-4 -4L4 4M4 -4L-4 4');
    expect(paths[3]).toHaveAttribute('stroke', 'var(--muted)');
    expect(screen.getByText('보급 끊김')).toBeInTheDocument();
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

  it('제어 모드: 화면 틀이 연 판을 쥐고, 단추 · Esc 는 onOpenChange 로만 알린다(다른 시트가 열리면 틀이 닫는다)', () => {
    const change = vi.fn();
    const { rerender } = render(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={() => undefined} legend={legend} open={null} onOpenChange={change} />);
    fireEvent.click(screen.getByRole('button', { name: '지도 레이어' }));
    expect(change).toHaveBeenLastCalledWith('layers');
    expect(screen.queryByRole('region', { name: '지도 레이어' })).toBeNull(); // 틀이 열기 전엔 안 열린다
    rerender(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={() => undefined} legend={legend} open="layers" onOpenChange={change} />);
    expect(screen.getByRole('region', { name: '지도 레이어' })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(change).toHaveBeenLastCalledWith(null);
    rerender(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={() => undefined} legend={legend} open={null} onOpenChange={change} />);
    expect(screen.queryByRole('region', { name: '지도 레이어' })).toBeNull();
  });

  it('판은 지도 상자 안에서 펼친다: 상자가 좁으면 단추 오른쪽 끝 ~ 상자 왼쪽 + 8 폭으로 줄이고, 넓으면 280', () => {
    // 모바일 작전실 지도 열(왼쪽 25 · 폭 151), 단추 묶음은 오른쪽 8 → 판 폭 = (176 − 8) − 25 − 8 = 135
    const rect = (left: number, right: number) => ({ x: left, y: 0, left, right, top: 0, bottom: 44, width: right - left, height: 44, toJSON: () => ({}) });
    let holderRight = 176;
    vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockImplementation(function (this: HTMLElement) { return this.parentElement; });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.dataset.testid === 'holder') return rect(25, holderRight);
      if (this.dataset.mapControl === 'layer-buttons') return rect(holderRight - 8 - 94, holderRight - 8);
      return rect(0, 0);
    });
    try {
      const { unmount } = render(<div data-testid="holder"><MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={() => undefined} legend={legend} compact /></div>);
      fireEvent.click(screen.getByRole('button', { name: '지도 레이어' }));
      expect(screen.getByRole('region', { name: '지도 레이어' }).style.width).toBe('135px');
      unmount();
      holderRight = 1025;
      render(<div data-testid="holder"><MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={() => undefined} legend={legend} /></div>);
      fireEvent.click(screen.getByRole('button', { name: '범례' }));
      expect(screen.getByRole('region', { name: '범례' }).style.width).toBe('280px');
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('좁은 화면(compact)은 글자 없는 단추라 이름은 aria-label 로 읽힌다', () => {
    render(<MapLayerButtons layers={DEFAULT_LAYERS} onLayersChange={() => undefined} legend={legend} compact />);
    expect(screen.getByRole('button', { name: '지도 레이어' })).toHaveTextContent('');
    expect(screen.getByRole('button', { name: '범례' })).toBeInTheDocument();
  });
});

describe('켠 층 남기기(설계서 §4.4)', () => {
  // 시험 환경(jsdom)에 저장소가 없을 수 있어 Map 으로 흉내 낸다(MapViewer 시험과 같은 방식)
  const store = new Map<string, string>();
  let blocked = false;
  const stubStorage = () => vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => { if (blocked) throw new Error('blocked'); store.set(key, value); },
    removeItem: (key: string) => store.delete(key),
    clear: () => store.clear(),
    key: () => null,
    get length() { return store.size; },
  });
  afterEach(() => { store.clear(); blocked = false; vi.unstubAllGlobals(); });

  it('남긴 글에서 아는 키의 참거짓만 받고, 빠진 키는 기본값 · 깨진 글은 통째로 기본값', () => {
    expect(parseStoredLayers(null, DEFAULT_LAYERS)).toBe(DEFAULT_LAYERS);
    expect(parseStoredLayers('{', DEFAULT_LAYERS)).toBe(DEFAULT_LAYERS);
    expect(parseStoredLayers('"fog"', DEFAULT_LAYERS)).toBe(DEFAULT_LAYERS);
    expect(parseStoredLayers(JSON.stringify({ fog: false, cityNames: 'no', unknown: true }), DEFAULT_LAYERS))
      .toEqual({ ...DEFAULT_LAYERS, fog: false });
  });

  it('붙은 뒤 남긴 값을 읽고, 바꾸면 다시 남긴다 — 저장소가 막혀도 바뀐 대로 그린다', () => {
    stubStorage();
    store.set(MAP_LAYERS_STORAGE_KEY, JSON.stringify({ countyLines: true }));
    const { result } = renderHook(() => useStoredMapLayers(DEFAULT_LAYERS));
    expect(result.current[0]).toEqual({ ...DEFAULT_LAYERS, countyLines: true });
    act(() => result.current[1]({ ...result.current[0], fog: false }));
    expect(JSON.parse(store.get(MAP_LAYERS_STORAGE_KEY)!)).toEqual({ ...DEFAULT_LAYERS, countyLines: true, fog: false });

    blocked = true;
    act(() => result.current[1]({ ...result.current[0], cityNames: false }));
    expect(result.current[0].cityNames).toBe(false);
  });

  it('저장소가 아예 없어도(읽기에서 던짐) 기본값으로 그린다', () => {
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('denied'); } });
    const { result } = renderHook(() => useStoredMapLayers(DEFAULT_LAYERS));
    expect(result.current[0]).toEqual(DEFAULT_LAYERS);
  });
});
