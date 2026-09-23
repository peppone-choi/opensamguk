/**
 * The active Han map carries 13 administrative 州 plus the separate 東夷 region.
 * Ten visual building traditions group nearby 州; they do not redefine jurisdiction.
 * The key is map/<code>.json cities[].meta.ju, exposed as MapPreviewCity.regionName.
 */
export const ARCHITECTURE_BY_JU = {
  사예: 'metropolitan',
  예주: 'central-plain',
  연주: 'central-plain',
  기주: 'hebei',
  청주: 'east-coast',
  서주: 'east-coast',
  유주: 'northern-frontier',
  병주: 'northern-frontier',
  량주: 'liangzhou',
  형주: 'jingzhou',
  양주: 'jiangdong',
  익주: 'sichuan',
  교주: 'lingnan',
} as const;

export type RegionalArchitecture = (typeof ARCHITECTURE_BY_JU)[keyof typeof ARCHITECTURE_BY_JU] | 'neutral';

export function architectureForJu(ju: string | null | undefined): RegionalArchitecture {
  if (ju && Object.hasOwn(ARCHITECTURE_BY_JU, ju)) {
    return ARCHITECTURE_BY_JU[ju as keyof typeof ARCHITECTURE_BY_JU];
  }
  return 'neutral';
}
