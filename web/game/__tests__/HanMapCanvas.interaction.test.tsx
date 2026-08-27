import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
  transforms: number[][];
  drawSmoothing: boolean[];
  radialGradients: unknown[];
  gradientFills: unknown[];
}

const records = new Map<HTMLCanvasElement, CanvasRecord>();
let pathConstructions = 0;
const pathRecords: { moves: number[][]; lines: number[][] }[] = [];

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
  const transforms: number[][] = [];
  const drawSmoothing: boolean[] = [];
  const radialGradients: unknown[] = [];
  const gradientFills: unknown[] = [];
  const gradient = { addColorStop: vi.fn() };
  const context = {
    canvas,
    createImageData: (width: number, height: number) => ({ data: new Uint8ClampedArray(width * height * 4) }),
    getImageData: (_x: number, _y: number, width: number, height: number) => {
      const data = new Uint8ClampedArray(width * height * 4);
      for (let offset = 3; offset < data.length; offset += 4) data[offset] = 255;
      return { data };
    },
    putImageData: (image: { data: Uint8ClampedArray }) => {
      putImages.push(new Uint8ClampedArray(image.data));
      operations.push('putImageData');
    },
    setTransform: vi.fn(),
    clearRect: () => operations.push('clearRect'),
    save: vi.fn(),
    restore: vi.fn(),
    transform: (...values: number[]) => transforms.push(values),
    drawImage: (source: unknown) => {
      drawImages.push(source);
      drawSmoothing.push(context.imageSmoothingEnabled);
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
    fill: () => {
      if (context.fillStyle === gradient) gradientFills.push(gradient);
    },
    fillRect: () => {
      const style = String(context.fillStyle);
      fillRects.push(style);
      operations.push(`fillRect:${style}`);
    },
    strokeRect: vi.fn(),
    arc: vi.fn(),
    closePath: vi.fn(),
    createRadialGradient: () => {
      radialGradients.push(gradient);
      return gradient;
    },
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
  const record = {
    context,
    operations,
    drawImages,
    putImages,
    strokes,
    strokeWidths,
    fillRects,
    transforms,
    drawSmoothing,
    radialGradients,
    gradientFills,
  };
  records.set(canvas, record);
  return record;
}

function politicalCompositions(): number {
  return [...records.values()].flatMap((record) => record.putImages)
    .filter((pixels) => pixels.some((value, index) => index % 4 === 3 && value === 96)).length;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => { resolve = next; });
  return { promise, resolve };
}

function pngResponse() {
  const u32 = (value: number) => new Uint8Array([
    (value >>> 24) & 0xff,
    (value >>> 16) & 0xff,
    (value >>> 8) & 0xff,
    value & 0xff,
  ]);
  const join = (...parts: Uint8Array[]) => {
    const result = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
    let offset = 0;
    for (const part of parts) {
      result.set(part, offset);
      offset += part.length;
    }
    return result;
  };
  const crc32 = (value: Uint8Array) => {
    let crc = 0xffffffff;
    for (const byte of value) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit += 1) {
        crc = (crc >>> 1) ^ ((crc & 1) === 1 ? 0xedb88320 : 0);
      }
    }
    return (crc ^ 0xffffffff) >>> 0;
  };
  const chunk = (kind: string, payload?: Uint8Array) => {
    const body = payload ?? new Uint8Array();
    const kindBytes = new TextEncoder().encode(kind);
    return join(
      u32(body.length),
      kindBytes,
      body,
      u32(crc32(join(kindBytes, body))),
    );
  };
  const ihdr = join(u32(4), u32(3), new Uint8Array([8, 2, 0, 0, 0]));
  const png = join(
    new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', new Uint8Array([0x78, 0x01])),
    chunk('IEND'),
  );
  return new Response(png, { status: 200, headers: { 'Content-Type': 'image/png' } });
}

describe('shared HanMapCanvas viewport interaction', () => {
  beforeEach(() => {
    records.clear();
    pathConstructions = 0;
    pathRecords.length = 0;
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
        private readonly record = { moves: [] as number[][], lines: [] as number[][] };
        constructor() {
          pathConstructions += 1;
          pathRecords.push(this.record);
        }
        moveTo(...values: number[]) { this.record.moves.push(values); }
        lineTo(...values: number[]) { this.record.lines.push(values); }
      },
      configurable: true,
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
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
    const terrainCanvas = [...records].find(([, record]) => (
      record.putImages.some((pixels) => pixels.some((value, index) => index % 4 === 3 && value === 255))
    ))?.[0];
    const politicalCanvas = [...records].find(([, record]) => (
      record.putImages.some((pixels) => pixels.some((value, index) => index % 4 === 3 && value === 96))
    ))?.[0];
    expect(main.drawImages.slice(-2)).toEqual([terrainCanvas, politicalCanvas]);
    expect(main.drawSmoothing.slice(-2)).toEqual([false, false]);
    expect(pathRecords).toEqual([
      { moves: [[1.5, 0.5]], lines: [[1.5, 1.5]] },
      { moves: [[1.5, 0.5]], lines: [[1.5, 1.5]] },
    ]);
    expect(main.transforms.at(-1)).toEqual([
      initial.scale,
      initial.scale / 2,
      -initial.scale,
      initial.scale / 2,
      initial.ox,
      initial.oy,
    ]);
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

    const markerOnly = CHE_OVERLAYS_FIXTURE.map((city, index) => (
      index === 0
        ? { ...city, name: '낙양성', level: 9, state: 2, supply: false, isCapital: false }
        : { ...city }
    ));
    rerender(
      <HanMapCanvas
        mapCode="che"
        tiles={{ ...CHE_TILES_FIXTURE, terrain: [...CHE_TILES_FIXTURE.terrain] }}
        provinceMap={PROVINCE_MAP}
        cities={markerOnly}
        sourceSize={{ width: 200, height: 120 }}
        onViewChange={(view) => views.push({ ...view })}
      />,
    );
    expect(politicalCompositions()).toBe(1);
    expect(pathConstructions).toBe(2);

    const equivalentCities = markerOnly.map((city) => ({ ...city }));
    rerender(
      <HanMapCanvas
        mapCode="che"
        tiles={{ ...CHE_TILES_FIXTURE, terrain: [...CHE_TILES_FIXTURE.terrain] }}
        provinceMap={PROVINCE_MAP}
        cities={equivalentCities}
        sourceSize={{ width: 200, height: 120 }}
        onViewChange={(view) => views.push({ ...view })}
      />,
    );
    expect(politicalCompositions()).toBe(1);
    expect(pathConstructions).toBe(2);

    const recolored = equivalentCities.map((city, index) => (
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

    rerender(
      <HanMapCanvas
        mapCode="che"
        tiles={CHE_TILES_FIXTURE}
        provinceMap={PROVINCE_MAP}
        cities={recolored}
        sourceSize={{ width: 400, height: 120 }}
        onViewChange={(view) => views.push({ ...view })}
      />,
    );
    expect(politicalCompositions()).toBe(3);
    expect(pathConstructions).toBe(2);

    expect(main.strokes).not.toContain('rgba(225, 192, 120, 0.72)');
    expect(main.fillRects).toContain('#8b8172');
    expect(main.fillRects).not.toContain('#ff0000');
    expect(main.fillRects).not.toContain('#0000ff');
    expect(main.radialGradients).toHaveLength(0);
    expect(main.gradientFills).toHaveLength(0);

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

  it('pinches around the current midpoint and keeps the remaining pointer panning after end or cancel', () => {
    const views: IsoView[] = [];
    render(<HanMapCanvas mapCode="che" tiles={CHE_TILES_FIXTURE} provinceMap={null} onViewChange={(view) => views.push({ ...view })} />);

    const canvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' });
    const initial = views.at(-1)!;
    const beforePinch = screenToCell(200, 100, initial);
    fireEvent.pointerDown(canvas, { clientX: 60, clientY: 50, pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerDown(canvas, { clientX: 140, clientY: 50, pointerId: 2, pointerType: 'touch' });
    fireEvent.pointerMove(canvas, { clientX: 160, clientY: 50, pointerId: 2, pointerType: 'touch' });

    const pinched = views.at(-1)!;
    expect(pinched.scale).toBeGreaterThan(initial.scale);
    const afterPinch = screenToCell(220, 100, pinched);
    expect(afterPinch[0]).toBeCloseTo(beforePinch[0], 6);
    expect(afterPinch[1]).toBeCloseTo(beforePinch[1], 6);

    fireEvent.pointerUp(canvas, { pointerId: 2, pointerType: 'touch' });
    fireEvent.pointerMove(canvas, { clientX: 80, clientY: 55, pointerId: 1, pointerType: 'touch' });
    const afterUpPan = views.at(-1)!;
    expect(afterUpPan.scale).toBe(pinched.scale);
    expect(afterUpPan).not.toEqual(pinched);

    fireEvent.pointerDown(canvas, { clientX: 160, clientY: 50, pointerId: 2, pointerType: 'touch' });
    fireEvent.pointerCancel(canvas, { pointerId: 2, pointerType: 'touch' });
    fireEvent.pointerMove(canvas, { clientX: 100, clientY: 60, pointerId: 1, pointerType: 'touch' });
    const afterCancelPan = views.at(-1)!;
    expect(afterCancelPan.scale).toBe(afterUpPan.scale);
    expect(afterCancelPan).not.toEqual(afterUpPan);
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

  it('uses fractional DPR for wheel, button, resize, and the exact 32 CSS-pixel cap', () => {
    Object.defineProperty(window, 'devicePixelRatio', { value: 0.8, configurable: true });
    const views: IsoView[] = [];
    render(
      <HanMapCanvas
        mapCode="che"
        tiles={CHE_TILES_FIXTURE}
        provinceMap={null}
        onViewChange={(view) => views.push({ ...view })}
      />,
    );
    const canvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' }) as HTMLCanvasElement;
    expect(canvas.width).toBe(160);
    const pointerCss = { x: 75, y: 53 };
    const pointer = {
      x: pointerCss.x * canvas.width / 200,
      y: pointerCss.y * canvas.height / 106,
    };
    const pointerCell = screenToCell(pointer.x, pointer.y, views.at(-1)!);
    for (let index = 0; index < 30; index += 1) {
      fireEvent.wheel(canvas, { clientX: pointerCss.x, clientY: pointerCss.y, deltaY: -1 });
    }
    const wheelMax = views.at(-1)!;
    expect(wheelMax.scale).toBeCloseTo(25.6, 9);
    expect(wheelMax.scale / 0.8).toBeCloseTo(32, 9);
    const afterWheel = screenToCell(pointer.x, pointer.y, wheelMax);
    expect(afterWheel[0]).toBeCloseTo(pointerCell[0], 6);
    expect(afterWheel[1]).toBeCloseTo(pointerCell[1], 6);

    fireEvent.click(screen.getByRole('button', { name: '지도 축소' }));
    const beforeButton = views.at(-1)!;
    const centeredCell = screenToCell(canvas.width / 2, canvas.height / 2, beforeButton);
    fireEvent.click(screen.getByRole('button', { name: '지도 확대' }));
    const afterButton = views.at(-1)!;
    const afterButtonCell = screenToCell(canvas.width / 2, canvas.height / 2, afterButton);
    expect(afterButton.scale).toBeLessThanOrEqual(25.6);
    expect(afterButtonCell[0]).toBeCloseTo(centeredCell[0], 6);
    expect(afterButtonCell[1]).toBeCloseTo(centeredCell[1], 6);

    const beforeResizeCenter = screenToCell(canvas.width / 2, canvas.height / 2, afterButton);
    Object.defineProperty(window, 'devicePixelRatio', { value: 1.25, configurable: true });
    fireEvent(window, new Event('resize'));
    const afterResize = views.at(-1)!;
    expect(afterResize.scale).toBeCloseTo(40, 9);
    expect(afterResize.scale / 1.25).toBeCloseTo(32, 9);
    const afterResizeCenter = screenToCell(canvas.width / 2, canvas.height / 2, afterResize);
    expect(afterResizeCenter[0]).toBeCloseTo(beforeResizeCenter[0], 6);
    expect(afterResizeCenter[1]).toBeCloseTo(beforeResizeCenter[1], 6);
  });

  it('keeps terrain when the province request rejects or dimensions mismatch', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 404 });
    const onMissing = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const rejected = render(<HanMapCanvas mapCode="che" tiles={CHE_TILES_FIXTURE} onMissing={onMissing} />);
    const rejectedCanvas = screen.getByRole('img', { name: 'che 아이소 타일 지도' }) as HTMLCanvasElement;

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/game/api/map/provinces?mapCode=che'));
    await waitFor(() => expect(recordFor(rejectedCanvas).drawImages.length).toBeGreaterThan(0));
    expect(new Set(recordFor(rejectedCanvas).drawImages).size).toBe(1);
    expect(politicalCompositions()).toBe(0);
    expect(onMissing).not.toHaveBeenCalled();
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

  it('ignores a stale province URL completion after the replacement map loads', async () => {
    const first = deferred<ReturnType<typeof pngResponse>>();
    const second = deferred<ReturnType<typeof pngResponse>>();
    const fetchMock = vi.fn((url: string) => url === '/first' ? first.promise : second.promise);
    const secondClose = vi.fn();
    const firstClose = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('createImageBitmap', vi.fn()
      .mockResolvedValueOnce({ width: 4, height: 3, close: secondClose })
      .mockResolvedValueOnce({ width: 4, height: 3, close: firstClose }));

    const view = render(
      <HanMapCanvas mapCode="che" tiles={CHE_TILES_FIXTURE} provinceUrl="/first" />,
    );
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/first'));
    view.rerender(
      <HanMapCanvas mapCode="che" tiles={CHE_TILES_FIXTURE} provinceUrl="/second" />,
    );
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/second'));

    await act(async () => { second.resolve(pngResponse()); });
    await waitFor(() => expect(pathConstructions).toBe(2));
    expect(secondClose).toHaveBeenCalledTimes(1);

    await act(async () => { first.resolve(pngResponse()); });
    await waitFor(() => expect(firstClose).toHaveBeenCalledTimes(1));
    expect(pathConstructions).toBe(2);
  });

  it('ignores a province completion after unmount while still closing its bitmap', async () => {
    const pending = deferred<ReturnType<typeof pngResponse>>();
    const close = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise));
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue({ width: 4, height: 3, close }));
    const view = render(
      <HanMapCanvas mapCode="che" tiles={CHE_TILES_FIXTURE} provinceUrl="/late" />,
    );
    view.unmount();

    await act(async () => { pending.resolve(pngResponse()); });
    await waitFor(() => expect(close).toHaveBeenCalledTimes(1));
    expect(pathConstructions).toBe(0);
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
