const CDN_BASE =
  "https://storage.hided.net/gitea/devsam/image/raw/branch/main/";

export function getPortraitUrl(picture?: string | null): string {
  if (!picture) return `${CDN_BASE}default.jpg`;
  return `${CDN_BASE}${picture}`;
}

export function getMapAssetUrl(asset: string): string {
  return `${CDN_BASE}${asset}`;
}

export function getCityLevelIcon(level: number): string {
  return `${CDN_BASE}cast_${level}.gif`;
}

export function getNationFlagUrl(color: string, supply: boolean): string {
  const prefix = supply ? "f" : "d";
  const hex = color.replace("#", "");
  return `${CDN_BASE}${prefix}${hex}.gif`;
}

export function getEventIcon(state: number): string {
  return `${CDN_BASE}event${state}.gif`;
}

export function getCrewTypeIconUrl(crewType: number): string {
  return `${CDN_BASE}crewtype${crewType}.png`;
}

export function getSammoBarBg(height: number): string {
  return `${CDN_BASE}pr${height - 2}.gif`;
}

export function getSammoBarFill(height: number): string {
  return `${CDN_BASE}pb${height - 2}.gif`;
}
