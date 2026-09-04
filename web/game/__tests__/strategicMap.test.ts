import { describe, expect, it } from 'vitest';
import { serverRoutePoints, strategicCapacityLabel, type StrategicMapScene } from '@opensamguk/ui';
import { STRATEGIC_BINDING, STRATEGIC_ROUTE, STRATEGIC_TOPOLOGY } from './fixtures/strategic-topology';

const scene: StrategicMapScene = { zones: [], byCell: new Map(),
  edgesById: new Map(STRATEGIC_TOPOLOGY.topology.traversalEdges.map(edge => [edge.id, edge])) };
const anchors = new Map([['land:45098', { col: 2, row: 3 }], ['land:45022', { col: 4, row: 3 }]]);

describe('server route source identity', () => {
  it('labels the unlimited dry-land sentinel without exposing an enormous convoy count', () => {
    expect(strategicCapacityLabel(2147483647, ['LAND'])).toBe('경로 처리 한도 없음');
    expect(strategicCapacityLabel(30, ['LAND', 'FERRY'])).toBe('수송대 처리 한도 30');
    expect(strategicCapacityLabel(2147483647, ['LAKE'])).toBe('수송대 처리 한도 2147483647');
  });
  it('projects the server ordered route with its original world and server identity', () => {
    expect(serverRoutePoints({ ...STRATEGIC_ROUTE, worldId: 1, serverId: 'pep' }, STRATEGIC_BINDING, scene, anchors, 'pep'))
      .toEqual([{ col: 2, row: 3 }, { col: 4, row: 3 }]);
  });
  it.each([{ worldId: 2, serverId: 'pep' }, { worldId: 1, serverId: 'other' }])(
    'rejects another world or server even when both use identical topology', origin => {
      expect(serverRoutePoints({ ...STRATEGIC_ROUTE, ...origin }, STRATEGIC_BINDING, scene, anchors, 'pep')).toBeNull();
    },
  );
  it('does not project a route when the browser has no selected server', () => {
    expect(serverRoutePoints({ ...STRATEGIC_ROUTE, worldId: 1, serverId: 'pep' }, STRATEGIC_BINDING, scene, anchors, undefined)).toBeNull();
  });
});
