import type { HanTiles, IsoCityOverlay } from '@opensamguk/ui';

export const CHE_TILES_FIXTURE: HanTiles = {
  _meta: {
    cols: 4,
    rows: 3,
    year: 200,
    terrainLegend: { 0: 'SEA', 1: 'PLAIN', 2: 'MOUNTAIN', 3: 'RIVER' },
    roadMaskBits: {},
  },
  terrain: ['0110', '1231', '0110'],
  owner: [[-1, 12]],
  seatOwner: [[-1, 12]],
  juns: [
    { name: '사예', nameCh: '司隸', seat: 0, col: 1, row: 1 },
    { name: '예주', nameCh: '豫州', seat: 1, col: 3, row: 2 },
  ],
  adjacency: {
    county: [{ a: 0, b: 1, cells: 2, cross: 'land' }],
    commandery: [],
  },
  regions: [],
  cities: [
    { id: '1', name: '낙양', nameCh: '雒陽', level: 8, kind: 'COMMANDERY', seat: true, col: 1, row: 1 },
    { id: '2', name: '허창', nameCh: '許昌', level: 6, kind: 'COUNTY', seat: true, col: 3, row: 2 },
  ],
};

export const CHE_OVERLAYS_FIXTURE: IsoCityOverlay[] = [
  {
    id: 11,
    name: '낙양',
    level: 8,
    nationId: 1,
    nationName: '위',
    nationColor: '#ff0000',
    x: 50,
    y: 40,
    state: 6,
    supply: true,
    isCapital: true,
  },
  {
    id: 22,
    name: '허창',
    level: 6,
    nationId: 2,
    nationName: '오',
    nationColor: '#0000ff',
    x: 150,
    y: 80,
    state: 0,
    supply: false,
    isCapital: false,
  },
];
