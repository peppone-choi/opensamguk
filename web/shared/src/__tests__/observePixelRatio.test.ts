import { describe, expect, it, vi } from 'vitest';
import { observePixelRatio } from '../iso/observePixelRatio';

describe('pixel density changes without a CSS resize', () => {
  it('schedules a draw, watches the new density, and removes the active listener', () => {
    const queries: { query: string; callback?: () => void; removeEventListener: ReturnType<typeof vi.fn> }[] = [];
    const source = { devicePixelRatio: 1, matchMedia: vi.fn((query: string) => {
      const item = { query, callback: undefined as (() => void) | undefined, removeEventListener: vi.fn() };
      queries.push(item);
      return { addEventListener: (_: string, cb: () => void) => { item.callback = cb; }, removeEventListener: item.removeEventListener };
    }) };
    const draw = vi.fn();
    const dispose = observePixelRatio(draw, source as unknown as Window);
    expect(queries[0].query).toBe('(resolution: 1dppx)');
    source.devicePixelRatio = 1.5;
    queries[0].callback!();
    expect(draw).toHaveBeenCalledTimes(1);
    expect(queries[0].removeEventListener).toHaveBeenCalledWith('change', queries[0].callback);
    expect(queries[1].query).toBe('(resolution: 1.5dppx)');
    dispose();
    expect(queries[1].removeEventListener).toHaveBeenCalledWith('change', queries[1].callback);
  });
});
