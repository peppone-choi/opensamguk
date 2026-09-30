// 부대 표지 그림: 모양에 관한 결정은 전부 여기 있다(자리 · 층 · 누를 영역은 corps.ts).
// 임시안이다 — 깃발/유닛 · 표식 모양 승인 묶음이 정해지기 전이라, 승인 뒤에는 이 파일만 바꾼다.
// 지금 모양: 움직이면 원작 부대 표지 A(빨강 묶음을 틀로, 주색 역할만 세력색) + 장수 깃발(제비꼬리 · 첫 글자),
// 멈추면 깃발만, 남은 경로는 세력색 점선.
import type { CorpsArt, Heading } from './corps';
import { drawFlag, drawSheetCell, type SpriteSheet } from './sprites';

/** markers.png: index = (shape × 6 + colour) × 5 + frame; shape A · red bundle = frames 0–3. */
const HEADING_FRAME: Record<Heading, number> = { left: 0, right: 1, up: 2, down: 3 };
const ROUTE_DASH = [6, 5];
const ROUTE_WIDTH = 2.5;

export interface KitCorpsArtDeps {
  /** Sheets arrive after the first terrain; null until then (nothing is drawn, the hit area stays). */
  sheets: () => { markers: SpriteSheet | null; flags: SpriteSheet | null };
  cached: (key: string, make: () => OffscreenCanvas) => OffscreenCanvas;
  font: string;
}

export function createKitCorpsArt({ sheets, cached, font }: KitCorpsArtDeps): CorpsArt {
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
      const { markers } = sheets();
      if (!markers) return;
      const art = cached(`corps|body|${marker.heading}|${marker.nationColor}`, () =>
        drawSheetCell(markers, HEADING_FRAME[marker.heading], marker.nationColor),
      );
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
