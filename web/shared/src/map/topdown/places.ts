// places.json(굽기 산출): 행정 소속 · 城 발자국 · 1칸 거점 · 관 · 이름표. 형식 정본은 K2 설계서 §2.3.
import type { LabelCandidate, LabelKind } from './labels';
import type { CityFootprint } from './hitTest';
import { juDisplayName } from '../juDisplay';

export type SiteKind = 'county' | 'ferry' | 'fort' | 'tribe';
type Cell = [number, number];

export interface PlaceCity {
  id: number;
  name: string;
  level: number;
  cell: Cell;
  provinceIndex: number;
  countyIndex: number;
  commanderyIndex: number;
  isSeat: boolean;
  footprint: { originCol: number; originRow: number; span: number; innerSpan: number };
  roofCell: Cell | null;
  gates: string;
  site: SiteKind | null;
  households: number | null;
}

export interface PlacesData {
  schemaVersion: 1;
  provinceCount: number;
  /** province index → [county, commandery, 州], -1 unknown. */
  provinceAdmin: [number, number, number][];
  counties: { id: string; name: string; kind: string; cityId: number | null }[];
  commanderies: { id: string; name: string; kind: string; seatCityId: number | null }[];
  ju: { name: string; anchor: Cell }[];
  cities: PlaceCity[];
  passes: { cityId: number; orientation: 'NS' | 'EW'; gateCells: Cell[]; wallCells: Cell[] }[];
  labels: { id: string; text: string; kind: LabelKind; anchor: Cell; priority: number; footprintSpan: number }[];
}

const LABEL_KINDS = new Set<LabelKind>(['ju', 'commandery', 'commanderySeat', 'county', 'pass', 'ferry']);

export function parsePlaces(value: unknown): PlacesData {
  const data = value as PlacesData;
  if (!data || data.schemaVersion !== 1) throw new Error('places: unsupported schemaVersion');
  if (!Array.isArray(data.provinceAdmin) || data.provinceAdmin.length !== data.provinceCount) {
    throw new Error(`places: provinceAdmin has ${data.provinceAdmin?.length} rows, expected ${data.provinceCount}`);
  }
  for (const label of data.labels) {
    if (!LABEL_KINDS.has(label.kind)) throw new Error(`places: unknown label kind ${label.kind}`);
  }
  return data;
}

/** Province plane value → county · commandery · 州 ids (+1, 0 = none) as RGBA16UI texels, 4096 wide. */
export function adminTexels(places: PlacesData, width = 4096): { width: number; height: number; data: Uint16Array } {
  const height = Math.ceil((places.provinceCount + 1) / width);
  const data = new Uint16Array(width * height * 4);
  places.provinceAdmin.forEach(([county, commandery, ju], index) => {
    const at = (index + 1) * 4;
    data[at] = county + 1;
    data[at + 1] = commandery + 1;
    data[at + 2] = ju + 1;
  });
  return { width, height, data };
}

/** 지도 이름표는 한글만: 동명 구분용 괄호 한자(「하양(河內郡)」)는 자리가 이미 구분하므로 뺀다. */
export function mapLabelText(text: string): string {
  return text.replace(/\s*\([^)]*[\u3400-\u9fff][^)]*\)\s*/g, '').trim() || text;
}

export function labelCandidates(places: PlacesData): LabelCandidate[] {
  return places.labels.map((label) => ({
    id: label.id,
    // 州 이름표는 bake 가 데이터 키(「량주」)를 싣는다 — 화면 이름(「서량」)으로 바꾼다(원장 D25)
    text: label.kind === 'ju' ? juDisplayName(mapLabelText(label.text)) : mapLabelText(label.text),
    kind: label.kind,
    anchor: { col: label.anchor[0], row: label.anchor[1] },
    priority: label.priority,
    footprintSpan: label.footprintSpan,
  }));
}

export function footprints(places: PlacesData): CityFootprint[] {
  return places.cities.map((city) => ({
    cityId: city.id,
    originCol: city.footprint.originCol,
    originRow: city.footprint.originRow,
    span: city.footprint.span,
  }));
}
