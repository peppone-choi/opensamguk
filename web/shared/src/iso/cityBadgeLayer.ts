import type { IsoCityBadge } from '../map/mapData';

export function cityBadgeAssetKey(badge: IsoCityBadge): string {
  if (badge.kind === 'waterway') return badge.feature;
  if (badge.kind === 'event') return String(badge.code);
  if (badge.kind === 'supply') return 'isolated';
  if (badge.kind === 'siege') return 'besieged';
  return 'works';
}

const SHORT_WORK: Record<string, string> = {
  IRRIGATION: '水', MILITARY_FARM: '屯', FORTIFICATION: '城', ROAD: '路',
  POST_STATION: '驛', WAREHOUSE: '倉', WATCHTOWER_BEACON: '烽',
  BARRACKS: '營', MARKET_WATERWAY: '市',
};

/** Image art is from opensamguk-images; the work-kind glyph and progress are their own layer. */
export function drawCityBadgeLayer(
  ctx: CanvasRenderingContext2D,
  badges: readonly IsoCityBadge[],
  images: ReadonlyMap<string, HTMLImageElement>,
  x: number,
  y: number,
): void {
  const size = 20;
  const gap = 2;
  ctx.save();
  ctx.imageSmoothingEnabled = false;
  for (let i = 0; i < badges.length; i += 1) {
    const badge = badges[i];
    const bx = Math.round(x + i * (size + gap));
    const by = Math.round(y);
    const image = images.get(cityBadgeAssetKey(badge));
    if (image) {
      ctx.drawImage(image, bx, by, size, size);
    } else {
      ctx.fillStyle = '#20201b';
      ctx.fillRect(bx, by, size, size);
      ctx.strokeStyle = '#eee8d5';
      ctx.strokeRect(bx + 0.5, by + 0.5, size - 1, size - 1);
    }
    if (badge.kind === 'waterway' && !image) {
      ctx.fillStyle = '#fffaf0';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.font = 'bold 14px serif';
      ctx.fillText(badge.feature === 'port' ? '港' : '渡', bx + 10, by + 10);
    }
    if (badge.kind === 'work') {
      ctx.fillStyle = badge.phase === 'active' ? '#20201b' : '#554739';
      ctx.fillRect(bx + 3, by + 9, 14, 10);
      ctx.fillStyle = '#fffaf0';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.font = 'bold 10px serif';
      ctx.fillText(SHORT_WORK[badge.work] ?? '工', bx + 10, by + 14);
      if (badge.phase === 'active') {
        ctx.fillStyle = '#f3d06b';
        ctx.fillRect(bx + 2, by + 18, Math.round(16 * Math.max(0, Math.min(100, badge.percent ?? 0)) / 100), 2);
      }
    }
  }
  ctx.restore();
}
