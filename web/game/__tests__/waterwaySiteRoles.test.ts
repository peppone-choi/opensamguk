import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { WATERWAY_SITE_ROLES } from '@opensamguk/ui';

describe('port and crossing silhouettes', () => {
  it('matches the curated waterway network to active Han city IDs exactly', () => {
    const root = resolve(process.cwd(), '../..');
    const network = JSON.parse(readFileSync(resolve(root, 'data/map/han-waterway-network-v1.json'), 'utf8')) as {
      nodes: { siteRef: { kind: string; id: string }; roles: string[] }[];
    };
    const map = JSON.parse(readFileSync(resolve(root, 'infra/src/main/resources/map/han-world-v3.json'), 'utf8')) as {
      cities: { id: number; spatialProvinceId?: string; physicalPlaceRef?: string }[];
    };
    const expected = new Map<number, string[]>();
    for (const node of network.nodes) {
      const matches = map.cities.filter((city) => node.siteRef.kind === 'CITY'
        ? city.spatialProvinceId === node.siteRef.id
        : city.physicalPlaceRef?.endsWith(`ss-${node.siteRef.id}`));
      expect(matches).toHaveLength(1);
      expected.set(matches[0].id, node.roles.map((role) => role === 'PORT' ? 'port' : 'ferry').sort());
    }
    expect(Object.keys(WATERWAY_SITE_ROLES)).toHaveLength(network.nodes.length);
    for (const [id, roles] of expected) expect([...WATERWAY_SITE_ROLES[id]].sort()).toEqual(roles);
  });
});
