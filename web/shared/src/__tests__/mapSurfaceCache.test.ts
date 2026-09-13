import { describe, expect, it, vi } from 'vitest';
import { MapSurfaceCache } from '../iso/mapSurfaceCache';

function fixture() {
  const context = { setTransform: vi.fn(), fillRect: vi.fn(), fillStyle: '', imageSmoothingEnabled: false };
  const canvas = { width: 0, height: 0, getContext: () => context } as unknown as HTMLCanvasElement;
  const cache = new MapSurfaceCache(() => canvas);
  const render = vi.fn(() => 123);
  return { cache, canvas, render };
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
  it('caps bitmap allocation and falls back for viewports exceeding the budget', () => {
    const { cache, render, canvas } = fixture();
    cache.get({ ...view, width: 1920, height: 1080 }, render);
    expect(canvas.width * canvas.height).toBeLessThanOrEqual(4_194_304);
    expect(cache.get({ ...view, width: 4000, height: 3000, dpr: 2 }, render)).toBeNull();
    expect(render).toHaveBeenCalledTimes(1);
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
