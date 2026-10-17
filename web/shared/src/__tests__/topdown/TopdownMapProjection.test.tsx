import { act, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// B4: TopdownMap 의 투영 스위치. 기본은 탑다운(OFF 화면 그대로), projection 을 넘기면 렌더러 · 맞춤 · 입력이 같은 투영을 쓴다.
const fake = vi.hoisted(() => ({ projections: [] as unknown[], disposed: 0 }));

vi.mock('../../map/topdown/renderer', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../map/topdown/renderer')>();
  class FakeRenderer {
    complete = Promise.resolve();
    placesData = null;
    constructor(_gl: unknown, _overlay: unknown, projection: unknown) {
      fake.projections.push(projection);
      return new Proxy(this, { get: (target, key) => (key in target ? target[key as keyof FakeRenderer] : () => undefined) });
    }
    load() {
      return Promise.resolve();
    }
    overviewPicture() {
      return null;
    }
    dispose() {
      fake.disposed += 1;
    }
  }
  return { ...actual, TopdownRenderer: FakeRenderer };
});

const { TopdownMap } = await import('../../map/topdown/TopdownMap');
const { MapMinimap, MINIMAP_SIZE } = await import('../../map/topdown/MapMinimap');
const { TOPDOWN_PROJECTION } = await import('../../map/topdown/projection');
const { DIMETRIC_PROJECTION } = await import('../../map/topdown/projection-dimetric');
const { panBy } = await import('../../map/topdown/input');

const source = { bakeUrl: '/bake', kitUrl: '/kit' };

describe('TopdownMap 투영 스위치(B4)', () => {
  const size = { width: 1440, height: 900 };
  beforeEach(() => {
    fake.projections = [];
    fake.disposed = 0;
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({
      x: 0, y: 0, left: 0, top: 0, right: size.width, bottom: size.height, width: size.width, height: size.height, toJSON: () => ({}) }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });
  const box = (container: HTMLElement) => container.querySelector('[data-map-status]')!;

  it('기본은 탑다운: 표지 data-projection=topdown, 렌더러도 탑다운, 맞춤 배율은 현행 그대로', async () => {
    const { container } = render(<TopdownMap source={source} />);
    await waitFor(() => expect(box(container).getAttribute('data-map-status')).toBe('ready'));
    expect(box(container).getAttribute('data-projection')).toBe('topdown');
    expect(fake.projections).toEqual([TOPDOWN_PROJECTION]);
    expect(box(container).getAttribute('data-map-zoom')).toBe((900 / 2676).toFixed(3));
  });

  it('다이메트릭: 표지 · 렌더러 · 맞춤 배율(가로 cols + rows, 세로 그 반)이 같은 투영을 쓴다', async () => {
    const { container } = render(<TopdownMap source={source} projection={DIMETRIC_PROJECTION} />);
    await waitFor(() => expect(box(container).getAttribute('data-map-status')).toBe('ready'));
    expect(box(container).getAttribute('data-projection')).toBe('dimetric');
    expect(fake.projections).toEqual([DIMETRIC_PROJECTION]);
    const span = 3072 + 2676;
    expect(box(container).getAttribute('data-map-zoom')).toBe(Math.min(1440 / span, 900 / (span / 2)).toFixed(3));
    expect(box(container).getAttribute('data-map-center')).toBe('1536.0,1338.0');
  });

  it('투영을 바꾸면 렌더러를 새로 만든다(셰이더가 투영마다 다르다)', async () => {
    const { container, rerender } = render(<TopdownMap source={source} />);
    await waitFor(() => expect(box(container).getAttribute('data-map-status')).toBe('ready'));
    rerender(<TopdownMap source={source} projection={DIMETRIC_PROJECTION} />);
    await waitFor(() => expect(box(container).getAttribute('data-projection')).toBe('dimetric'));
    expect(fake.projections).toEqual([TOPDOWN_PROJECTION, DIMETRIC_PROJECTION]);
    expect(fake.disposed).toBe(1);
  });

  it('다이메트릭 방향키는 화면 방향으로 옮긴다(화면 작은 변의 1/4)', async () => {
    const start = { center: { col: 1500, row: 900 }, zoom: 16 };
    const { container } = render(<TopdownMap source={source} projection={DIMETRIC_PROJECTION} initialView={start} />);
    await waitFor(() => expect(box(container).getAttribute('data-map-status')).toBe('ready'));
    fireEvent.keyDown(box(container), { key: 'ArrowRight' });
    const expected = panBy(start, -900 / 4, 0, DIMETRIC_PROJECTION).center;
    expect(box(container).getAttribute('data-map-center')).toBe(`${expected.col.toFixed(1)},${expected.row.toFixed(1)}`);
    // 오른쪽으로 보기 = 칸 열은 늘고 행은 준다(화면 오른쪽 = 열 +, 행 −)
    expect(expected.col).toBeGreaterThan(1500);
    expect(expected.row).toBeLessThan(900);
  });
});

describe('MapMinimap 지금 보는 곳(B4)', () => {
  afterEach(() => vi.restoreAllMocks());
  function recorder() {
    const calls: [string, ...number[]][] = [];
    // 그리기 호출만 적는 가짜 2D 문맥: 속성(strokeStyle 등)은 담아 두고, 나머지 이름은 부르면 적는다
    const props: Record<string, unknown> = {};
    const ctx = new Proxy({} as CanvasRenderingContext2D, {
      get: (_target, key: string) => (key in props ? props[key] : (...args: number[]) => { calls.push([key, ...args]); }),
      set: (_target, key: string, value) => { props[key] = value; return true; },
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx);
    return calls;
  }
  const shape = { cols: 3072, rows: 2676 };
  const camera = { center: { col: 1500, row: 900 }, zoom: 4 };
  const viewport = { width: 1280, height: 720, dpr: 1 };
  const picture = {} as OffscreenCanvas;

  it('탑다운은 현행 사각형 그대로', () => {
    const calls = recorder();
    render(<MapMinimap picture={picture} shape={shape} camera={camera} viewport={viewport} onJump={() => {}} />);
    expect(calls.filter(([name]) => name === 'strokeRect')).toHaveLength(1);
    expect(calls.some(([name]) => name === 'lineTo')).toBe(false);
  });

  it('다이메트릭은 화면 네 귀의 칸을 잇는 기운 사각형', () => {
    const calls = recorder();
    render(<MapMinimap picture={picture} shape={shape} camera={camera} viewport={viewport} onJump={() => {}} projection={DIMETRIC_PROJECTION} />);
    expect(calls.some(([name]) => name === 'strokeRect')).toBe(false);
    const sx = MINIMAP_SIZE.width / shape.cols;
    const sy = MINIMAP_SIZE.height / shape.rows;
    const points = calls.filter(([name]) => name === 'moveTo' || name === 'lineTo').slice(0, 4).map(([, x, y]) => [x, y]);
    const expected = [[0, 0], [1280, 0], [1280, 720], [0, 720]].map(([x, y]) => {
      const cell = DIMETRIC_PROJECTION.screenToCell({ x, y }, camera, viewport);
      return [cell.col * sx, cell.row * sy];
    });
    expect(points).toEqual(expected);
  });

  it('작은 지도 누르기는 투영과 상관없이 칸 격자 자리', () => {
    recorder();
    const jumps: { col: number; row: number }[] = [];
    const { getByRole } = render(<MapMinimap picture={picture} shape={shape} camera={camera} viewport={viewport} onJump={(cell) => jumps.push(cell)}
      projection={DIMETRIC_PROJECTION} />);
    const button = getByRole('button');
    vi.spyOn(button, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: MINIMAP_SIZE.width, height: MINIMAP_SIZE.height } as DOMRect);
    act(() => { fireEvent.click(button, { clientX: 88, clientY: 76.5, detail: 1 }); });
    expect(jumps).toEqual([{ col: 1536, row: 1338 }]);
  });
});
