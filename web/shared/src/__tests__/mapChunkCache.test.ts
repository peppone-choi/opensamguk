import { describe, expect, it, vi } from 'vitest';
import { MapChunkCache } from '../iso/mapChunkCache';
const view = { width: 1000, height: 600, scale: 0.02, dpr: 1, panX: .25, panY: .5 };
function fixture() {
  const canvases: HTMLCanvasElement[] = [];
  const cache = new MapChunkCache(() => {
    const canvas = { width: 0, height: 0, getContext: () => ({ setTransform() {}, fillRect() {}, imageSmoothingEnabled: false }) } as unknown as HTMLCanvasElement;
    canvases.push(canvas); return canvas;
  });
  const render = vi.fn(() => 100);
  const fill = (next = view) => {
    let result = cache.get(next, render, true)!;
    for (let i = 0; result.pending && i < 100; i++) result = cache.get(next, render, true)!;
    expect(result.pending).toBe(false);
    return result;
  };
  return { cache, canvases, render, fill };
}
describe('chunked map surface', () => {
  it('builds one chunk per frame, then reuses visible and prefetched chunks', () => {
    const { cache, render, fill } = fixture();
    expect(cache.get(view, render, true)!.pending).toBe(true);
    expect(render).toHaveBeenCalledTimes(1);
    fill(); const count = render.mock.calls.length;
    fill(); expect(render).toHaveBeenCalledTimes(count);
  });
  it('keeps existing chunks while zooming and refines only after settling', () => {
    const { cache, render, fill } = fixture();
    const before = fill(); const count = render.mock.calls.length;
    const zoomed = { ...view, scale: view.scale * 1.3 };
    const preview = cache.get(zoomed, render, false)!;
    expect(preview.surfaces.length).toBeGreaterThan(0);
    expect(render).toHaveBeenCalledTimes(count);
    expect(preview.surfaces.some(s => before.surfaces.includes(s))).toBe(true);
    const final = fill(zoomed);
    expect(final.surfaces.every(s => s.scale === zoomed.scale)).toBe(true);
    expect(render.mock.calls.length).toBeGreaterThan(count);
  });
  it('keeps visible fallback terrain during the first refinement frame', () => {
    const { cache, render, fill } = fixture();
    fill();
    const next = cache.get({ ...view, scale: .026 }, render, true)!;
    expect(next.surfaces.some(({bounds:b}) => 22000 >= b.x && 22000 < b.x+b.width
      && 10000 >= b.y && 10000 < b.y+b.height)).toBe(true);
  });
  it('preserves previous viewport coverage through every refinement frame', () => {
    const { cache, render, fill } = fixture();
    fill(); const next = { ...view, scale: .022 };
    for (let frame = 0; frame < 20; frame++) {
      const result = cache.get(next, render, true)!;
      for (let x = 25; x < next.width; x += 100) for (let y = 25; y < next.height; y += 100) {
        const wx=(x-next.panX)/next.scale, wy=(y-next.panY)/next.scale;
        expect(result.surfaces.some(({bounds:b})=>wx>=b.x && wx<b.x+b.width && wy>=b.y && wy<b.y+b.height)).toBe(true);
      }
      if (!result.pending) break;
    }
  });
  it('renders only missing regions on pan and remains bounded after many moves', () => {
    const { cache, render, canvases, fill } = fixture();
    fill(); const count = render.mock.calls.length;
    fill({ ...view, panX: 20.25 });
    expect(render).toHaveBeenCalledTimes(count);
    for (let i = 1; i < 20; i++) {
      const start = render.mock.calls.length;
      cache.get({ ...view, panX: i * 700 }, render, true);
      expect(render.mock.calls.length - start).toBeLessThanOrEqual(1);
      expect(canvases.reduce((sum, c) => sum + c.width * c.height, 0)).toBeLessThanOrEqual(4_194_304);
    }
    cache.dispose(); expect(canvases.every(c => c.width === 0 && c.height === 0)).toBe(true);
  });
  it.each([[1600,900,2],[4000,3000,2],[5000,300,1],[300,5000,1]])('covers %i × %i DPR %s without fallback', (width,height,dpr) => {
    const { fill, canvases } = fixture();
    const next = { ...view,width,height,dpr };
    const result = fill(next);
    for(const x of [0, width/2, width-.01]) for(const y of [0,height/2,height-.01]) {
      const wx=(x-next.panX)/next.scale, wy=(y-next.panY)/next.scale;
      expect(result.surfaces.some(({bounds:b})=>wx>=b.x && wx<=b.x+b.width && wy>=b.y && wy<=b.y+b.height)).toBe(true);
    }
    expect(canvases.reduce((sum,c)=>sum+c.width*c.height,0)).toBeLessThanOrEqual(4_194_304);
  });
  it('falls back only when a drawing context cannot be created', () => {
    const cache = new MapChunkCache(() => ({getContext:()=>null}) as unknown as HTMLCanvasElement);
    expect(cache.get(view,()=>0,true)).toBeNull();
  });
});
