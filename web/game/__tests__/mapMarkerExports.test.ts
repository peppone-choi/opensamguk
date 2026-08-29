import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { CITY_MARKER_SPECS } from '@opensamguk/ui';

interface MarkerManifest {
  version: number;
  pixelRatio: number;
  designSha256: string;
  markers: Record<string, {
    file: string;
    width: number;
    height: number;
    anchor: [number, number];
    sha256: string;
  }>;
}

const roots = [
  resolve(process.cwd(), 'public/map/markers'),
  resolve(process.cwd(), '../gateway/public/map/markers'),
];

function sha256(path: string) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

describe('map marker export contract', () => {
  it('keeps both web exports byte-identical and aligned with the runtime specs', () => {
    const manifests = roots.map((root) => JSON.parse(
      readFileSync(resolve(root, 'manifest.json'), 'utf8'),
    ) as MarkerManifest);

    expect(manifests[1]).toEqual(manifests[0]);
    expect(manifests[0].pixelRatio).toBe(2);
    expect(manifests[0].designSha256).toMatch(/^[0-9a-f]{64}$/);
    for (const [tier, marker] of Object.entries(manifests[0].markers)) {
      const runtime = CITY_MARKER_SPECS[tier as keyof typeof CITY_MARKER_SPECS];
      expect(marker).toMatchObject({
        width: runtime.pixelWidth,
        height: runtime.pixelHeight,
        anchor: [runtime.anchorX, runtime.anchorY],
      });
      expect(manifests[0].pixelRatio).toBe(runtime.pixelRatio);
      expect(sha256(resolve(roots[0], marker.file))).toBe(marker.sha256);
      expect(sha256(resolve(roots[1], marker.file))).toBe(marker.sha256);
    }
  });
});
