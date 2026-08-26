import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  HanMapCanvas,
  screenToCell,
  type IsoView,
  type ProvinceIdentityMap,
} from '@opensamguk/ui';
import { CHE_OVERLAYS_FIXTURE, CHE_TILES_FIXTURE } from './fixtures/che-tiles';

interface CanvasRecord {
  context: CanvasRenderingContext2D;
  operations: string[];
  drawImages: unknown[];
  putImages: Uint8ClampedArray[];
  strokes: string[];
  strokeWidths: { style: string; width: number }[];
  fillRects: string[];
}

const records = new Map<HTMLCanvasElement, CanvasRecord>();
let pathConstructions = 0;

const PROVINCE_MAP: ProvinceIdentityMap = {
  width: 4,
  height: 3,
  provinces: new Int16Array([
    -1, 0, 0, -1,
    -1, 0, 1, -1,
    -1, -1, -1, 1,
  ]),
  commanderies: new Int16Array([
    -1, 0, 0, -1,
    -1, 0, 1, -1,
    -1, -1, -1, 1,
  ]),
  provinceEdges: [{ x1: 1.5, y1: 0.5, x2: 1.5, y2: 1.5 }],
  commanderyEdges: [{ x1: 1.5, y1: 0.5, x2: 1.5, y2: 1.5 }],
};

function recordFor(canvas: HTMLCanvasElement): CanvasRecord {
  const existing = records.get(canvas);
  if (existing) return existing;

  const operations: string[] = [];
  const drawImages: unknown[] = [];
  const putImages: Uint8ClampedArray[] = [];
  const strokes: string[] = [];
  const strokeWidths: { style: string; width: number }[] = [];
  const fillRects: string[] = [];
  const gradient = { addColorStop: vi.fn() };
  const context = {
    canvas,
    createImageData: (width: number, height: number) => ({ data: new Uint8ClampedArray(width * height * 4) }),
    getImageData: (_x: number, _y: number, width: number, height: number) => ({ data: new Uint8ClampedArray(width * height * 4) }),
    putImageData: (image: { data: Uint8ClampedArray }) => {
      putImages.push(new Uint8ClampedArray(image.data));
      operations.push('putImageData');
    },
    setTransform: vi.fn(),
    clearRect: () => operations.push('clearRect'),
    save: vi.fn(),
    restore: vi.fn(),
    transform: vi.fn(),
    drawImage: (source: unknown) => {
      drawImages.push(source);
      operations.push('drawImage');
    },
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: () => {
      const style = String(context.strokeStyle);
      strokes.push(style);
      strokeWidths.push({ style, width: context.lineWidth });
      operations.push(`stroke:${style}`);
    },
    fill: vi.fn(),
    fillRect: () => {
      const style = String(context.fillStyle);
      fillRects.push(style);
      operations.push(`fillRect:${style}`);
    },
    strokeRect: vi.fn(),
    arc: vi.fn(),
    closePath: vi.fn(),
    createRadialGradient: () => gradient,
    fillText: vi.fn(),
    strokeText: vi.fn(),
    imageSmoothingEnabled: true,
    strokeStyle: '',
    fillStyle: '',
    lineWidth: 1,
    font: '',
    textAlign: 'start',
    textBaseline: 'alphabetic',
    globalAlpha: 1,
  } as unknown as CanvasRenderingContext2D;
  const record = { context, operations, drawImages, putImages, strokes, strokeWidths, fillRects };
  records.set(canvas, record);
  return record;
}

function politicalCompositions(): number {
  return [...records.values()].flatMap((record) => record.putImages)
    .filter((pixels) => pixels.some((value, index) => index % 4 === 3 && value === 96)).length;
}

describe('shared HanMapCanvas viewport interaction', () => {
  beforeEach(() => {
    records.clear();
    pathConstructions = 0;
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function getContext(this: HTMLCanvasElement) {
      return recordFor(this).context;
    });
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(200);
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 0, left: 0, top: 0, right: 200, bottom: 106, width: 200, height: 106, toJSON: () => ({}),
    });
    Object.defineProperty(window, 'devicePixelRatio', { value: 2, configurable: true });
    Object.defineProperty(globalThis, 'Path2D', {
      value: class Path2DStub {
        constructor() { pathConstructions += 1; }
        moveTo() {}
        lineTo() {}
      },
      configurable: true,
    });
  });

  it('reuses political pixels and cached borders while zooming and panning', () => {
    const views: IsoView[] = [];
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const { rerender } = render(
      <HanMapCanvas
        mapCode="che"
        tiles={CHE_TILES_FIXTURE}
        provinceMap={PROVINCE_MAP}
        cities={CHE_OVERLAYS_FIXTURE}
        sourceSize={{ width: 200, height: 120 }}
        onViewChange={(view) => views.push({ ...view })}
      />,
    );

    expect(politicalCompositions()).toBe(1);
    expect(pathConstructions).toBe(2);
    expect(fetchMock).not.toHaveBeenCalled();
    const canvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' }) as HTMLCanvasElement;
    const main = recordFor(canvas);
    const initialDraws = main.drawImages.length;
    const initial = views.at(-1)!;
    const pointer = { x: 210, y: 110 };
    const before = screenToCell(pointer.x, pointer.y, initial);

    fireEvent.wheel(canvas, { clientX: pointer.x / 2, clientY: pointer.y / 2, deltaY: -1 });
    const zoomed = views.at(-1)!;
    const after = screenToCell(pointer.x, pointer.y, zoomed);
    expect(zoomed.scale).toBeGreaterThan(14);
    expect(after[0]).toBeCloseTo(before[0], 6);
    expect(after[1]).toBeCloseTo(before[1], 6);

    fireEvent.pointerDown(canvas, { clientX: 100, clientY: 50, pointerId: 1 });
    fireEvent.pointerMove(canvas, { clientX: 103, clientY: 52, pointerId: 1 });
    expect(main.drawImages.length).toBeGreaterThan(initialDraws);
    expect(politicalCompositions()).toBe(1);
    expect(pathConstructions).toBe(2);

    const recolored = CHE_OVERLAYS_FIXTURE.map((city, index) => (
      index === 0 ? { ...city, nationColor: '#00ff00' } : city
    ));
    rerender(
      <HanMapCanvas
        mapCode="che"
        tiles={CHE_TILES_FIXTURE}
        provinceMap={PROVINCE_MAP}
        cities={recolored}
        sourceSize={{ width: 200, height: 120 }}
        onViewChange={(view) => views.push({ ...view })}
      />,
    );
    expect(politicalCompositions()).toBe(2);
    expect(pathConstructions).toBe(2);

    expect(main.strokes).not.toContain('rgba(225, 192, 120, 0.72)');
    expect(main.fillRects).toContain('#8b8172');
    expect(main.fillRects).not.toContain('#ff0000');
    expect(main.fillRects).not.toContain('#0000ff');

    const frame = main.operations.slice(main.operations.lastIndexOf('clearRect'));
    const terrain = frame.indexOf('drawImage');
    const political = frame.indexOf('drawImage', terrain + 1);
    const province = frame.indexOf('stroke:rgba(18,20,22,0.58)');
    const commanderyDark = frame.indexOf('stroke:rgba(10,12,14,0.82)');
    const commanderyLight = frame.indexOf('stroke:rgba(225,210,163,0.76)');
    const castle = frame.indexOf('fillRect:#8b8172');
    const order = [terrain, political, province, commanderyDark, commanderyLight, castle];
    expect(order.every((index) => index >= 0)).toBe(true);
    expect(order).toEqual([...order].sort((a, b) => a - b));

    const scale = views.at(-1)!.scale;
    expect(main.strokeWidths.findLast(({ style }) => style === 'rgba(18,20,22,0.58)')?.width)
      .toBeCloseTo(2 / scale, 9);
    expect(main.strokeWidths.findLast(({ style }) => style === 'rgba(10,12,14,0.82)')?.width)
      .toBeCloseTo(6 / scale, 9);
    expect(main.strokeWidths.findLast(({ style }) => style === 'rgba(225,210,163,0.76)')?.width)
      .toBeCloseTo(3 / scale, 9);
  });

  it('changes the view for zoom controls and pointer panning', () => {
    const views: IsoView[] = [];
    render(<HanMapCanvas mapCode="che" tiles={CHE_TILES_FIXTURE} provinceMap={null} onViewChange={(view) => views.push({ ...view })} />);

    const initial = views.at(-1)!;
    fireEvent.click(screen.getByRole('button', { name: '지도 확대' }));
    const zoomed = views.at(-1)!;
    expect(zoomed.scale).toBeGreaterThan(initial.scale);

    const canvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' });
    fireEvent.pointerDown(canvas, { clientX: 100, clientY: 100, pointerId: 1 });
    fireEvent.pointerMove(canvas, { clientX: 130, clientY: 115, pointerId: 1 });
    expect(views.at(-1)).not.toEqual(zoomed);
  });

  it('preserves the centered cell and CSS zoom when DPR changes', () => {
    Object.defineProperty(window, 'devicePixelRatio', { value: 1, configurable: true });
    const views: IsoView[] = [];
    render(
      <HanMapCanvas
        mapCode="che"
        tiles={CHE_TILES_FIXTURE}
        provinceMap={null}
        onViewChange={(view) => views.push({ ...view })}
      />,
    );
    const canvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' });
    fireEvent.wheel(canvas, { clientX: 100, clientY: 53, deltaY: -1 });
    fireEvent.wheel(canvas, { clientX: 100, clientY: 53, deltaY: -1 });
    const before = views.at(-1)!;
    const beforeCenter = screenToCell(100, 53, before);
    expect(before.scale).toBe(32);

    Object.defineProperty(window, 'devicePixelRatio', { value: 2, configurable: true });
    fireEvent(window, new Event('resize'));

    const after = views.at(-1)!;
    expect(after.scale).toBe(64);
    const afterCenter = screenToCell(200, 106, after);
    expect(afterCenter[0]).toBeCloseTo(beforeCenter[0], 6);
    expect(afterCenter[1]).toBeCloseTo(beforeCenter[1], 6);
  });

  it('keeps terrain when the province request rejects or dimensions mismatch', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 404 });
    vi.stubGlobal('fetch', fetchMock);
    const rejected = render(<HanMapCanvas mapCode="che" tiles={CHE_TILES_FIXTURE} />);
    const rejectedCanvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' }) as HTMLCanvasElement;

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/game/api/map/provinces?mapCode=che'));
    await waitFor(() => expect(recordFor(rejectedCanvas).drawImages.length).toBeGreaterThan(0));
    expect(new Set(recordFor(rejectedCanvas).drawImages).size).toBe(1);
    expect(politicalCompositions()).toBe(0);
    rejected.unmount();

    records.clear();
    const mismatch = render(
      <HanMapCanvas
        mapCode="che"
        tiles={CHE_TILES_FIXTURE}
        provinceMap={{ ...PROVINCE_MAP, width: 3 }}
        cities={CHE_OVERLAYS_FIXTURE}
      />,
    );
    const mismatchCanvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' }) as HTMLCanvasElement;
    expect(recordFor(mismatchCanvas).drawImages.length).toBeGreaterThan(0);
    expect(new Set(recordFor(mismatchCanvas).drawImages).size).toBe(1);
    expect(politicalCompositions()).toBe(0);
    mismatch.unmount();
  });

  it('focuses and activates canvas city markers from the keyboard', () => {
    const onCityActivate = vi.fn();
    render(
      <HanMapCanvas
        mapCode="che"
        tiles={CHE_TILES_FIXTURE}
        provinceMap={null}
        cities={CHE_OVERLAYS_FIXTURE}
        sourceSize={{ width: 200, height: 120 }}
        onCityActivate={onCityActivate}
      />,
    );
    const canvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' });
    fireEvent.focus(canvas);
    fireEvent.keyDown(canvas, { key: 'Enter' });
    expect(onCityActivate).toHaveBeenCalledWith(
      expect.objectContaining(CHE_OVERLAYS_FIXTURE[0]),
      { pointerType: 'keyboard' },
    );
  });
});
