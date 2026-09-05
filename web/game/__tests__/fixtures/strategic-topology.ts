// Hand-checked presentation fixture, not historical evidence or executable production topology.
export const STRATEGIC_BINDING = {
  worldId: 1, mapCode: 'han-world-v3' as const, topologyRevision: 'reviewed-test',
  topologyHash: 'b'.repeat(64), baseTilesSha256: 'a'.repeat(64), cols: 4, rows: 3,
};

export const STRATEGIC_TOPOLOGY = {
  binding: STRATEGIC_BINDING,
  topology: {
    landProvinceIds: ['45098', '45022'],
    geometries: [
      { id: 'geometry:coast', terrainCode: 0, cellCount: 1, cellRuns: [{ row: 0, startCol: 0, endCol: 0 }] },
      { id: 'geometry:lake', terrainCode: 4, cellCount: 1, cellRuns: [{ row: 2, startCol: 3, endCol: 3 }] },
    ],
    waterZones: [
      { id: 'water-zone:coast', kind: 'COASTAL_SEA' as const, geometryRef: 'geometry:coast',
        connectionStatus: 'ISOLATED_NO_REVIEWED_CONNECTION', confidence: 'REVIEWED', seasonalAvailability: 'ALWAYS' },
      { id: 'water-zone:lake', kind: 'LAKE_BASIN' as const, geometryRef: 'geometry:lake',
        connectionStatus: 'ISOLATED_NO_REVIEWED_CONNECTION', confidence: 'REVIEWED', seasonalAvailability: 'ALWAYS' },
    ],
    traversalEdges: [{ id: 'land-boundary:45022<>45098', from: 'land:45022', to: 'land:45098', mode: 'LAND',
      movementCost: 1, capacity: 2147483647, seasonalAvailability: 'ALWAYS', supplyAllowed: true }],
    riverBarriers: [], ports: [], activationBlockerCodes: ['NO_REVIEWED_PORT_OR_LANDING_EVIDENCE'],
  },
  controlVisibility: 'REDACTED' as const,
  controls: [
    { waterZoneId: 'water-zone:coast', status: 'UNKNOWN' as const, controllingNationId: null, contestingNationIds: [], revision: null },
    { waterZoneId: 'water-zone:lake', status: 'UNKNOWN' as const, controllingNationId: null, contestingNationIds: [], revision: null },
  ],
};

export const STRATEGIC_ROUTE = {
  nodeKeys: ['land:45098', 'land:45022'], edgeIds: ['land-boundary:45022<>45098'], modes: ['LAND'],
  totalCost: 1, capacity: 2147483647, topologyRevision: STRATEGIC_BINDING.topologyRevision,
  topologyHash: STRATEGIC_BINDING.topologyHash, pathHash: 'c'.repeat(64),
};
