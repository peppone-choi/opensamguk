import { act, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 뒤로 미룬 자료(밉 · 개관 · 장소 · 그림 판)의 실패가 화면 상태로 올라오는지만 본다. GL은 가짜 렌더러로 대신한다.
const fake = vi.hoisted(() => ({
  complete: Promise.resolve() as Promise<void>,
  disposed: 0,
  places: null as null | { cities: { id: number; footprint: { originCol: number; originRow: number; span: number; innerSpan: number } }[] },
  selected: [] as (number | null)[],
  pinAvoid: [] as (null | { col: number; row: number })[],
  me: [] as unknown[],
  layers: [] as Record<string, boolean>[],
  corps: [] as { id: string }[][],
  labelAvoid: [] as unknown[],
  picture: undefined as unknown,
}));

vi.mock('../../map/topdown/renderer', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../map/topdown/renderer')>();
  class FakeRenderer {
    complete = fake.complete;
    get placesData() {
      return fake.places;
    }
    setSelectedCity(id: number | null) {
      fake.selected.push(id);
    }
    setPinAvoid(at: null | { col: number; row: number }) {
      fake.pinAvoid.push(at);
    }
    setMe(me: unknown) {
      fake.me.push(me);
    }
    setLayers(layers: Record<string, boolean>) {
      fake.layers.push(layers);
    }
    setCorps(corps: { id: string }[]) {
      fake.corps.push(corps);
    }
    setLabelAvoid(boxes: unknown) {
      fake.labelAvoid.push(boxes);
    }
    overviewPicture() {
      return fake.picture;
    }
    constructor() {
      return new Proxy(this, { get: (target, key) => (key in target ? target[key as keyof FakeRenderer] : () => undefined) });
    }
    load() {
      return Promise.resolve();
    }
    dispose() {
      fake.disposed += 1;
    }
  }
  return { ...actual, TopdownRenderer: FakeRenderer };
});

const { TopdownMap } = await import('../../map/topdown/TopdownMap');
const { DEFAULT_LAYERS } = await import('../../map/topdown/renderer');

const source = { bakeUrl: '/bake', kitUrl: '/kit' };

describe('TopdownMap 뒤로 미룬 자료', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('장소 표를 받지 못하면 지형이 보여도 오류로 알린다', async () => {
    fake.complete = Promise.reject(new Error('/bake/places.json.gz: HTTP 404'));
    fake.complete.catch(() => undefined);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { container, getByRole } = render(<TopdownMap source={source} />);
    const box = container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(box.getAttribute('data-map-status')).toBe('error'));
    // 화면에는 쉬운 말 안내만, 서버 원문 · 파일 이름은 콘솔에만
    expect(getByRole('alert')).toHaveTextContent('지도를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.');
    expect(getByRole('alert')).not.toHaveTextContent('places.json.gz');
    expect(warn).toHaveBeenCalledWith('[탑다운 지도] 불러오지 못함', expect.objectContaining({ message: '/bake/places.json.gz: HTTP 404' }));
    warn.mockRestore();
  });

  it('notices={false} 면 안내문은 그리지 않고 상태만 onStatus 로 넘긴다', async () => {
    fake.complete = Promise.reject(new Error('/bake/places.json.gz: HTTP 404'));
    fake.complete.catch(() => undefined);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const seen: string[] = [];
    const { container, queryByRole } = render(<TopdownMap source={source} notices={false} onStatus={(status) => seen.push(status)} />);
    const box = container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(box.getAttribute('data-map-status')).toBe('error'));
    expect(queryByRole('alert')).toBeNull();
    expect(seen).toContain('error');
    warn.mockRestore();
  });

  it('城으로 이동 + 선택(focusCity): 장소 표에 있으면 누른 것처럼 city 를 내고, 없으면 false', async () => {
    fake.complete = Promise.resolve();
    fake.places = { cities: [{ id: 7, footprint: { originCol: 10, originRow: 20, span: 3, innerSpan: 1 } }] };
    const onSelect = vi.fn();
    let handle: import('../../map/topdown/TopdownMap').TopdownMapHandle | null = null;
    render(<TopdownMap source={source} onSelect={onSelect} onReady={(next) => { handle = next; }} />);
    await waitFor(() => expect(handle).not.toBeNull());
    expect(handle!.focusCity(999)).toBe(false);
    expect(onSelect).not.toHaveBeenCalled();
    expect(handle!.focusCity(7)).toBe(true);
    expect(onSelect).toHaveBeenCalledWith({ kind: 'city', id: 7, cell: { col: 11, row: 21 } });
    fake.places = null;
  });

  it('고른 城은 렌더러 테두리로 넘긴다(바뀔 때마다, 지우면 null)', async () => {
    fake.complete = Promise.resolve();
    fake.selected = [];
    const { container, rerender } = render(<TopdownMap source={source} selectedCityId={7} />);
    await waitFor(() => expect(container.querySelector('[data-map-status]')!.getAttribute('data-map-status')).toBe('ready'));
    expect(fake.selected).toContain(7);
    rerender(<TopdownMap source={source} selectedCityId={null} />);
    expect(fake.selected.at(-1)).toBeNull();
  });

  it('내 위치를 지도 위 DOM 층이 그리면(meOverlay) 캔버스는 핀을 그리지 않고, 이름표가 핀 끝 자리를 피한다', async () => {
    fake.complete = Promise.resolve();
    fake.pinAvoid = [];
    fake.me = [];
    const me = { cell: { col: 1400.5, row: 900.5 }, state: 'IN_CITY' as const, nationColor: null, portrait: null, name: '하후돈' };
    const { container, rerender } = render(<TopdownMap source={source} me={me} meOverlay />);
    await waitFor(() => expect(container.querySelector('[data-map-status]')!.getAttribute('data-map-status')).toBe('ready'));
    expect(fake.me.at(-1)).toBeNull();
    expect(fake.pinAvoid.at(-1)).toEqual({ col: 1400.5, row: 900.5 });
    rerender(<TopdownMap source={source} me={me} />);
    expect(fake.me.at(-1)).toBe(me);
    expect(fake.pinAvoid.at(-1)).toBeNull();
  });

  it('레이어를 바꾸면 렌더러가 그 층으로 다시 그린다(단추 콜백에서 그림까지, M2-7)', async () => {
    fake.complete = Promise.resolve();
    fake.layers = [];
    const off = { ...DEFAULT_LAYERS, cityNames: false, corpsRoutes: false };
    const { container, rerender } = render(<TopdownMap source={source} layers={DEFAULT_LAYERS} />);
    await waitFor(() => expect(container.querySelector('[data-map-status]')!.getAttribute('data-map-status')).toBe('ready'));
    expect(fake.layers.at(-1)).toEqual(DEFAULT_LAYERS);
    rerender(<TopdownMap source={source} layers={off} />);
    expect(fake.layers.at(-1)).toEqual(off);
  });

  it('부대 표지를 렌더러에 넘기고, 뿌리에 그 수를 남긴다(없으면 0)', async () => {
    fake.complete = Promise.resolve();
    fake.corps = [];
    const marker = { id: 'c1', cell: { col: 3, row: 4 }, nationColor: '#b03a2e', leaderName: '하후돈', heading: null };
    const { container, rerender } = render(<TopdownMap source={source} corps={[marker]} />);
    const root = () => container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(root().getAttribute('data-map-status')).toBe('ready'));
    expect(fake.corps.at(-1)?.map((entry) => entry.id)).toEqual(['c1']);
    expect(root().getAttribute('data-map-corps')).toBe('1');
    rerender(<TopdownMap source={source} />);
    expect(fake.corps.at(-1)).toEqual([]);
    expect(root().getAttribute('data-map-corps')).toBe('0');
  });

  it('다 받으면 준비 상태로 남는다', async () => {
    fake.complete = Promise.resolve();
    const { container } = render(<TopdownMap source={source} />);
    const box = container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(box.getAttribute('data-map-status')).toBe('ready'));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(box.getAttribute('data-map-status')).toBe('ready');
  });
});

describe('TopdownMap 첫 맞춤 · 이름표 피할 상자(게이트웨이 배경 · 로비, K5)', () => {
  let size = { width: 1440, height: 900 };
  let resize: (() => void) | null = null;
  beforeEach(() => {
    size = { width: 1440, height: 900 };
    resize = null;
    vi.stubGlobal('ResizeObserver', class { constructor(cb: () => void) { resize = cb; } observe() {} unobserve() {} disconnect() {} });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({
      x: 0, y: 0, left: 0, top: 0, right: size.width, bottom: size.height, width: size.width, height: size.height, toJSON: () => ({}) }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });
  const zoomOf = (container: HTMLElement) => container.querySelector('[data-map-status]')!.getAttribute('data-map-zoom');

  it("'cover'는 빈 띠 없이 채우고, 손대기 전에는 상자가 바뀌면 다시 채운다 — 손댄 뒤에는 그대로", async () => {
    fake.complete = Promise.resolve();
    const { container } = render(<TopdownMap source={source} initialView="cover" />);
    // 1440×900 → max(1440/3072, 900/2676) = 0.469
    await waitFor(() => expect(zoomOf(container)).toBe((1440 / 3072).toFixed(3)));
    size = { width: 800, height: 900 };
    act(() => resize?.());
    expect(zoomOf(container)).toBe((900 / 2676).toFixed(3));
    // 사용자가 확대하면(키보드 +) 그 뒤 크기가 바뀌어도 다시 채우지 않는다
    fireEvent.keyDown(container.querySelector('[data-map-status]')!, { key: '+' });
    const zoomed = zoomOf(container);
    expect(zoomed).not.toBe((900 / 2676).toFixed(3));
    size = { width: 1440, height: 900 };
    act(() => resize?.());
    expect(zoomOf(container)).toBe(zoomed);
  });

  // K8 봉토 지도(보드 V31K8Vassals 460×260): 칸 여럿을 한 조각에 — 상자 크기를 잰 뒤 계산한다
  it("cells 첫 보기는 그 칸들이 다 드는 가장 큰 멈춤 자리(현 보기 이하)와 가운데", async () => {
    fake.complete = Promise.resolve();
    size = { width: 460, height: 260 };
    const { container } = render(<TopdownMap source={source} initialView={{ cells: [{ col: 1400, row: 900 }, { col: 1410, row: 905 }] }} />);
    // 11 × 6 칸, 여백 32 → (460−64)/11 = 36 · (260−64)/6 = 32.7 → 현 보기 16 이하의 가장 큰 멈춤 자리 16
    await waitFor(() => expect(zoomOf(container)).toBe('16.000'));
    expect(container.querySelector('[data-map-status]')!.getAttribute('data-map-center')).toBe('1405.5,903.0');
  });

  // 실지도 결함 2: 모바일 작전실 지도 열이 151px일 때 176px 작은 지도가 조작 단추와 몰렸다. 상자의 반을 넘으면 두지 않는다
  it('작은 지도는 넓은 상자에만 — 좁아지면 빠지고 다시 넓어지면 돌아온다', async () => {
    fake.complete = Promise.resolve();
    fake.picture = { width: 768, height: 669 };
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null); // 작은 지도 그리기는 보지 않는다
    size = { width: 800, height: 560 };
    const { queryByRole } = render(<TopdownMap source={source} minimap />);
    const minimap = () => queryByRole('button', { name: /작은 지도/ });
    await waitFor(() => expect(minimap()).not.toBeNull());
    size = { width: 151, height: 560 };
    act(() => resize?.());
    expect(minimap()).toBeNull();
    // 문턱: 가로 176 × 2 + 24 = 376, 세로 153 × 2 + 24 = 330
    size = { width: 376, height: 329 };
    act(() => resize?.());
    expect(minimap()).toBeNull();
    size = { width: 376, height: 330 };
    act(() => resize?.());
    expect(minimap()).not.toBeNull();
    fake.picture = undefined;
  });

  it("'fit'은 그대로 — 처음만 전체 맞춤이고 상자가 바뀌어도 다시 맞추지 않는다", async () => {
    fake.complete = Promise.resolve();
    const { container } = render(<TopdownMap source={source} />);
    await waitFor(() => expect(zoomOf(container)).toBe((900 / 2676).toFixed(3)));
    // 800×900 이면 다시 맞출 때 min(800/3072, 900/2676) = 0.260 이 된다 — 그대로 0.336 이어야 한다
    size = { width: 800, height: 900 };
    act(() => resize?.());
    expect(zoomOf(container)).toBe((900 / 2676).toFixed(3));
  });

  it('이름표가 피할 상자를 렌더러에 넘기고, 바뀌면 다시 넘긴다(없으면 빈 목록)', async () => {
    fake.complete = Promise.resolve();
    fake.labelAvoid = [];
    const panel = [{ x: 40, y: 60, width: 420, height: 520 }];
    const { container, rerender } = render(<TopdownMap source={source} labelAvoid={panel} />);
    await waitFor(() => expect(container.querySelector('[data-map-status]')!.getAttribute('data-map-status')).toBe('ready'));
    expect(fake.labelAvoid.at(-1)).toBe(panel);
    const moved = [{ x: 0, y: 0, width: 200, height: 80 }];
    rerender(<TopdownMap source={source} labelAvoid={moved} />);
    expect(fake.labelAvoid.at(-1)).toBe(moved);
    rerender(<TopdownMap source={source} />);
    expect(fake.labelAvoid.at(-1)).toEqual([]);
  });
});
