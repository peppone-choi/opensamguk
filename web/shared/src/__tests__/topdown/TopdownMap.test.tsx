import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 뒤로 미룬 자료(밉 · 개관 · 장소 · 그림 판)의 실패가 화면 상태로 올라오는지만 본다. GL은 가짜 렌더러로 대신한다.
const fake = vi.hoisted(() => ({
  complete: Promise.resolve() as Promise<void>,
  disposed: 0,
  places: null as null | { cities: { id: number; footprint: { originCol: number; originRow: number; span: number; innerSpan: number } }[] },
  selected: [] as (number | null)[],
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
    const { container, getByRole } = render(<TopdownMap source={source} />);
    const box = container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(box.getAttribute('data-map-status')).toBe('error'));
    expect(getByRole('alert')).toHaveTextContent('places.json.gz: HTTP 404');
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

  it('다 받으면 준비 상태로 남는다', async () => {
    fake.complete = Promise.resolve();
    const { container } = render(<TopdownMap source={source} />);
    const box = container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(box.getAttribute('data-map-status')).toBe('ready'));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(box.getAttribute('data-map-status')).toBe('ready');
  });
});
