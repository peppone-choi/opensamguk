const DEFAULT_CDN_BASE =
  "https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-image@master/";

function normalizeCdnBase(url: string): string {
  return `${url.replace(/\/+$/, "")}/`;
}

export const CDN_BASE = normalizeCdnBase(
  process.env.NEXT_PUBLIC_IMAGE_CDN_BASE ?? DEFAULT_CDN_BASE,
);

export const CDN_ROOT = CDN_BASE.slice(0, -1);
export const GAME_CDN_ROOT = `${CDN_ROOT}/game`;
export const ICON_CDN_ROOT = `${CDN_ROOT}/icons`;

export function getPortraitUrl(picture?: string | null): string {
  if (!picture) return `${ICON_CDN_ROOT}/0.jpg`;
  // picture is a numeric string (e.g. "1146") — append .jpg
  if (/^\d+$/.test(picture)) return `${ICON_CDN_ROOT}/${picture}.jpg`;
  // Already has extension or is a full path
  return `${CDN_BASE}${picture}`;
}

export function getMapAssetUrl(asset: string): string {
  return `${GAME_CDN_ROOT}/${asset}`;
}

export function getCityLevelIcon(level: number): string {
  return `${GAME_CDN_ROOT}/cast_${level}.gif`;
}

export function getNationFlagUrl(color: string, supply: boolean): string {
  const prefix = supply ? "f" : "d";
  const hex = color.replace("#", "");
  return `${GAME_CDN_ROOT}/${prefix}${hex}.gif`;
}

export function getEventIcon(state: number): string {
  return `${GAME_CDN_ROOT}/event${state}.gif`;
}

export function getCrewTypeIconUrl(crewType: number): string {
  return `${GAME_CDN_ROOT}/crewtype${crewType}.png`;
}

export function getSammoBarBg(height: number): string {
  return `${GAME_CDN_ROOT}/pr${height - 2}.gif`;
}

export function getSammoBarFill(height: number): string {
  return `${GAME_CDN_ROOT}/pb${height - 2}.gif`;
}
