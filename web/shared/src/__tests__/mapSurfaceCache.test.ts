import { describe, expect, it, vi } from 'vitest';
import { MapSurfaceCache } from '../iso/mapSurfaceCache';

function fixture() {
  const context = { setTransform: vi.fn(), fillRect: vi.fn(), fillStyle: '', imageSmoothingEnabled: false };
  const canvas = { width: 0, height: 0, getContext: () => context } as unknown as HTMLCanvasElement;
  const cache = new MapSurfaceCache(() => canvas);
  const render = vi.fn(() => 123);
  return { cache, canvas, context, render };
}
const view = { width: 1000, height: 600, panX: 0, panY: 0, scale: 0.02, dpr: 1 };

describe('static map surface', () => {
  it('reuses terrain while panning within overscan and redraws when uncovered', () => {
    const { cache, render } = fixture();
    expect(cache.get(view, render)?.tiles).toBe(123);
    cache.get({ ...view, panX: 100, panY: 80 }, render);
    expect(render).toHaveBeenCalledTimes(1);
    cache.get({ ...view, panX: 900 }, render);
    expect(render).toHaveBeenCalledTimes(2);
  });
  it('rebuilds at a new zoom or pixel density and covers resized views', () => {
    const { cache, render } = fixture();
    cache.get(view, render);
    cache.get({ ...view, scale: 0.03 }, render);
    cache.get({ ...view, scale: 0.03, dpr: 2 }, render);
    cache.get({ ...view, scale: 0.03, dpr: 2, width: 1600 }, render);
    expect(render).toHaveBeenCalledTimes(4);
  });
  it('redraws scale-dependent borders even when scale times density is unchanged', () => {
    const { cache, render } = fixture();
    cache.get(view, render);
    cache.get({ ...view, scale: view.scale / 2, dpr: 2 }, render);
    expect(render).toHaveBeenCalledTimes(2);
  });
  it.each([
    [1600, 900, 2], [4000, 3000, 2], [5000, 300, 1], [300, 5000, 1],
    [2048, 2048, 1], [1537, 865, 1.75],
  ])('keeps a bounded reusable surface at %i × %i DPR %s', (width, height, dpr) => {
    const { cache, render, canvas, context } = fixture();
    const large = { ...view, width, height, dpr, panX: 0.25, panY: 0.5 };
    const surface = cache.get(large, render);
    expect(surface).not.toBeNull();
    expect(canvas.width * canvas.height).toBeLessThanOrEqual(4_194_304);
    expect(Math.max(canvas.width, canvas.height)).toBeLessThanOrEqual(4096);
    const bounds = surface!.bounds;
    expect(bounds.x).toBeLessThanOrEqual(-large.panX / large.scale);
    expect(bounds.y).toBeLessThanOrEqual(-large.panY / large.scale);
    expect(bounds.x + bounds.width).toBeGreaterThanOrEqual((width - large.panX) / large.scale);
    expect(bounds.y + bounds.height).toBeGreaterThanOrEqual((height - large.panY) / large.scale);
    const [ratio, , , , tx, ty] = context.setTransform.mock.calls[0];
    expect(bounds.x * ratio + tx).toBeCloseTo(0);
    expect(bounds.y * ratio + ty).toBeCloseTo(0);
    expect(bounds.width * ratio).toBeCloseTo(canvas.width);
    expect(bounds.height * ratio).toBeCloseTo(canvas.height);
    expect(cache.get(large, render)).toBe(surface);
    expect(cache.get({ ...large, panX: 20.25, panY: 20.5 }, render)).toBe(surface);
    expect(render).toHaveBeenCalledTimes(1);
    cache.get({ ...large, panX: width }, render);
    expect(render).toHaveBeenCalledTimes(2);
  });
  it('keeps native pixel density when the overscan fits', () => {
    const { cache, context } = fixture();
    cache.get(view, () => 0);
    expect(context.setTransform.mock.calls[0][0]).toBe(view.scale * view.dpr);
  });
  it('restores native density after shrinking a large viewport', () => {
    const { cache } = fixture();
    expect(cache.get({ ...view, width: 4000, height: 3000, dpr: 2 }, () => 0)!.pixelRatio).toBeLessThan(2);
    expect(cache.get(view, () => 0)!.pixelRatio).toBe(1);
  });
  it.each([
    { width: 0 }, { height: -1 }, { dpr: 0 }, { scale: Number.NaN },
    { width: Number.POSITIVE_INFINITY }, { panX: Number.NaN },
  ])('does not allocate for an invalid view %j', invalid => {
    const { cache, canvas, render } = fixture();
    expect(cache.get({ ...view, ...invalid }, render)).toBeNull();
    expect(canvas.width * canvas.height).toBe(0);
    expect(render).not.toHaveBeenCalled();
  });
  it('releases backing memory and redraws after disposal', () => {
    const { cache, render, canvas } = fixture();
    cache.get(view, render);
    cache.dispose();
    expect(canvas.width * canvas.height).toBe(0);
    cache.get(view, render);
    expect(render).toHaveBeenCalledTimes(2);
  });
  it('reuses a budget-sized surface at a fractional pan without rebuilding every frame', () => {
    const { cache, render } = fixture();
    const edge = { ...view, width: 2048, height: 2048, panX: 0.25, panY: 0.5 };
    cache.get(edge, render);
    cache.get(edge, render);
    expect(render).toHaveBeenCalledTimes(1);
  });
  it('supports browsers without an offscreen 2D context', () => {
    const cache = new MapSurfaceCache(() => ({ getContext: () => null }) as unknown as HTMLCanvasElement);
    expect(cache.get(view, vi.fn())).toBeNull();
  });
});
