import { describe, expect, it } from 'vitest';
import { elevationAssetsForGrid } from '../iso/useIsoTileGrid';

describe('elevation frame selection', () => {
  it('keeps old saved worlds on their original DEM', () => {
    expect(elevationAssetsForGrid(768, 669).levels).toContain('legacy-levels');
    expect(elevationAssetsForGrid(864, 843).levels).not.toContain('legacy');
  });
  it('rejects an unsupported frame instead of silently shifting mountains', () => {
    expect(() => elevationAssetsForGrid(800, 700)).toThrow('지원하지 않는');
  });
});
