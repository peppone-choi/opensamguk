import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 뒤로 미룬 자료(밉 · 개관 · 장소 · 그림 판)의 실패가 화면 상태로 올라오는지만 본다. GL은 가짜 렌더러로 대신한다.
const fake = vi.hoisted(() => ({
  complete: Promise.resolve() as Promise<void>,
  disposed: 0,
  places: null as null | { cities: { id: number; footprint: { originCol: number; originRow: number; span: number; innerSpan: number } }[] },
  selected: [] as (number | null)[],
  layers: [] as Record<string, boolean>[],
  corps: [] as { id: string }[][],
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
    setLayers(layers: Record<string, boolean>) {
      fake.layers.push(layers);
    }
    setCorps(corps: { id: string }[]) {
      fake.corps.push(corps);
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
