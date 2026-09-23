/** Map markers from the same Hwiha reads used by the domestic and siege panels. */
import { citySnapshotBadges, type IsoCityBadge } from '@opensamguk/ui';

export const WORK_BADGE_LABELS = {
  IRRIGATION: '수리',
  MILITARY_FARM: '둔전',
  FORTIFICATION: '성방',
  ROAD: '도로',
  POST_STATION: '역참',
  WAREHOUSE: '창고',
  WATCHTOWER_BEACON: '망루봉화',
  BARRACKS: '병영',
  MARKET_WATERWAY: '시장수운',
} as const;

export type WorkBadgeCode = keyof typeof WORK_BADGE_LABELS;

interface CityInput {
  readonly id: number;
  readonly state?: number;
  readonly supply?: boolean;
  readonly nationId: number;
}

interface WorksInput {
  readonly status: string;
  readonly counties: readonly {
    readonly countyId: number;
    readonly active: { readonly work: string; readonly label: string; readonly percent: number } | null;
    readonly completed: readonly { readonly work: string; readonly label: string }[];
  }[];
}

interface SiegesInput {
  readonly status: string;
  readonly sieges: readonly { readonly countyId: number; readonly status: string }[];
}

function isWorkBadgeCode(code: string): code is WorkBadgeCode {
  return Object.hasOwn(WORK_BADGE_LABELS, code);
}

/** Only IDs in the rendered city list may receive server read overlays. */
export function cityBadgesById(
  cities: readonly CityInput[],
  works: WorksInput | null,
  sieges: SiegesInput | null,
): ReadonlyMap<number, readonly IsoCityBadge[]> {
  const workByCounty = new Map(works?.status === 'READY'
    ? works.counties.map((county) => [county.countyId, county] as const) : []);
  const besieged = new Set(sieges?.status === 'READY'
    ? sieges.sieges.filter((siege) => siege.status === 'ACTIVE').map((siege) => siege.countyId) : []);
  const byId = new Map<number, readonly IsoCityBadge[]>();

  for (const city of cities) {
    const badges: IsoCityBadge[] = [];
    badges.push(...citySnapshotBadges(city));

    const county = workByCounty.get(city.id);
    if (county?.active && isWorkBadgeCode(county.active.work)) {
      badges.push({ kind: 'work', work: county.active.work, label: WORK_BADGE_LABELS[county.active.work],
        phase: 'active', percent: Math.max(0, Math.min(100, county.active.percent)) });
    }
    for (const complete of county?.completed ?? []) {
      if (isWorkBadgeCode(complete.work)) {
        badges.push({ kind: 'work', work: complete.work, label: WORK_BADGE_LABELS[complete.work], phase: 'completed' });
      }
    }
    if (besieged.has(city.id)) badges.push({ kind: 'siege' });
    if (badges.length > 0) byId.set(city.id, badges);
  }

  return byId;
}
