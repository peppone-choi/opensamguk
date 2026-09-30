// 부대 표지 그림: 모양에 관한 결정은 전부 여기 있다(자리 · 층 · 누를 영역은 corps.ts).
// 분대 표기 B안(사용자 승인 2026-09-30): 원작 전투 유닛 그림을 역할층만 세력색으로 칠해 몸통으로 쓰고,
// 머리 위에 장수 깃발(제비꼬리 · 첫 글자)을 꽂는다. 남은 경로는 세력색 점선.
// 유닛 키트를 아직 못 받았으면(또는 원천이 없으면) 움직이는 부대만 원작 지도 표지 A로 그린다.
import { unitFrame, unitRamp, unitSpriteRgba, UNIT_SIZE, type UnitFacing, type UnitKit } from '../../battle/battleUnits';
import type { CorpsArt, Heading } from './corps';
import { drawFlag, drawSheetCell, type SpriteSheet } from './sprites';

/** markers.png: index = (shape × 6 + colour) × 5 + frame; shape A · red bundle = frames 0–3. */
const HEADING_FRAME: Record<Heading, number> = { left: 0, right: 1, up: 2, down: 3 };
/** Map heading → battle facing (the original's x − 1 · y − 1 · x + 1 · y + 1 order); standing still faces SW. */
export const FACING_OF_HEADING: Record<Heading, UnitFacing> = { left: 'sw', up: 'nw', right: 'ne', down: 'se' };
const ROUTE_DASH = [6, 5];
const ROUTE_WIDTH = 2.5;

export interface KitCorpsArtDeps {
  /** Sheets arrive after the first terrain; null until then (nothing is drawn, the hit area stays). */
  sheets: () => { markers: SpriteSheet | null; flags: SpriteSheet | null };
  /** Battle unit sprites (B안); null until loaded or when the map has no unit source. */
  units?: () => UnitKit | null;
  cached: (key: string, make: () => OffscreenCanvas) => OffscreenCanvas;
  font: string;
}

function unitCanvas(kit: UnitKit, frame: number, colour: string): OffscreenCanvas {
  const out = new OffscreenCanvas(UNIT_SIZE, UNIT_SIZE);
  out.getContext('2d')!.putImageData(new ImageData(unitSpriteRgba(kit, frame, unitRamp(colour)), UNIT_SIZE, UNIT_SIZE), 0, 0);
  return out;
}

export function createKitCorpsArt({ sheets, units, cached, font }: KitCorpsArtDeps): CorpsArt {
  return {
    drawRoute(ctx, marker, points) {
      if (points.length < 2) return;
      ctx.save();
      ctx.setLineDash(ROUTE_DASH);
      ctx.lineWidth = ROUTE_WIDTH;
      ctx.strokeStyle = marker.nationColor;
      ctx.beginPath();
      ctx.moveTo(points[0].x, points[0].y);
      for (const p of points.slice(1)) ctx.lineTo(p.x, p.y);
      ctx.stroke();
      ctx.restore();
    },
    drawBody(ctx, marker, rect) {
      const kit = units?.() ?? null;
      if (kit) {
        const facing = marker.heading ? FACING_OF_HEADING[marker.heading] : 'sw';
        const frame = unitFrame(marker.unitType ?? 'leader', facing, 'idle', 0);
        const art = cached(`corps|unit|${frame}|${marker.nationColor}`, () => unitCanvas(kit, frame, marker.nationColor));
        const smoothing = ctx.imageSmoothingEnabled;
        ctx.imageSmoothingEnabled = false;
        ctx.drawImage(art, rect.x, rect.y, rect.width, rect.height);
        ctx.imageSmoothingEnabled = smoothing;
        return;
      }
      const { markers } = sheets();
      if (!markers || !marker.heading) return;
      const heading = marker.heading;
      const art = cached(`corps|body|${heading}|${marker.nationColor}`, () => drawSheetCell(markers, HEADING_FRAME[heading], marker.nationColor));
      ctx.drawImage(art, rect.x, rect.y, rect.width, rect.height);
    },
    drawFlag(ctx, marker, rect) {
      const { flags } = sheets();
      if (!flags) return;
      const letter = [...marker.leaderName][0] ?? '';
      const art = cached(`flag|swallow|${marker.nationColor}|${letter}`, () =>
        drawFlag(flags, 'swallow', marker.nationColor, letter, rect.width / 16, font),
      );
      ctx.drawImage(art, rect.x, rect.y, rect.width, rect.height);
    },
  };
}
