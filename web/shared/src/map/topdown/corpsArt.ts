// 부대 표지 그림: 모양에 관한 결정은 전부 여기 있다(자리 · 층 · 누를 영역은 corps.ts).
// 임시안이다 — 깃발/유닛 · 표식 모양 승인 묶음이 정해지기 전이라, 승인 뒤에는 이 파일만 바꾼다.
// 지금 모양: 움직이면 원작 부대 표지 A(빨강 묶음을 틀로, 주색 역할만 세력색) + 장수 깃발(제비꼬리 · 첫 글자),
// 멈추면 깃발만, 남은 경로는 세력색 점선.
// 상태(ADR-LITE-049 개정 · 원장 §1 D34, 보드 V31K2CorpsStates): 첩보는 α 0.55 + 점선 테두리 + 「?」, 내 군단은 토큰 청동 #d3b064 2px,
// 병력 띠는 현 보기에서만(자리 · 뺄지는 corps.ts placeCorpsBands).
import type { CorpsArt, Heading } from './corps';
import { drawFlag, drawSheetCell, type SpriteSheet } from './sprites';

/** markers.png: index = (shape × 6 + colour) × 5 + frame; shape A · red bundle = frames 0–3. */
const HEADING_FRAME: Record<Heading, number> = { left: 0, right: 1, up: 2, down: 3 };
const ROUTE_DASH = [6, 5];
const ROUTE_WIDTH = 2.5;
/** 첩보(마지막 목격) 흐림 — 옛 지도 값 그대로(D34). */
export const CORPS_INTEL_ALPHA = 0.55;
/** 내 군단 테두리 — 디자인 토큰 청동(--bronze, D34 「가」). */
export const CORPS_OWN_STROKE = '#d3b064';
const INK = 'rgba(18,12,6,0.92)';
const INTEL_DASH = [3, 2];
export const CORPS_BAND_FONT_PX = 12;
export const CORPS_BAND_PAD_X = 6;
export const CORPS_BAND_HEIGHT = 18;

function withStandingAlpha(ctx: CanvasRenderingContext2D, intel: boolean, draw: () => void): void {
  if (!intel) {
    draw();
    return;
  }
  ctx.save();
  ctx.globalAlpha *= CORPS_INTEL_ALPHA;
  draw();
  ctx.restore();
}

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
      // 원작 16px 표지를 정수 배로 늘리니 이웃 화소를 섞지 않는다
      withStandingAlpha(ctx, marker.standing === 'intel', () => {
        const smoothing = ctx.imageSmoothingEnabled;
        ctx.imageSmoothingEnabled = false;
        ctx.drawImage(art, rect.x, rect.y, rect.width, rect.height);
        ctx.imageSmoothingEnabled = smoothing;
      });
    },
    drawFlag(ctx, marker, rect) {
      const { flags } = sheets();
      if (!flags) return;
      const letter = [...marker.leaderName][0] ?? '';
      const art = cached(`flag|swallow|${marker.nationColor}|${letter}`, () =>
        drawFlag(flags, 'swallow', marker.nationColor, letter, rect.width / 16, font),
      );
      withStandingAlpha(ctx, marker.standing === 'intel', () => ctx.drawImage(art, rect.x, rect.y, rect.width, rect.height));
    },
    drawStanding(ctx, marker, rect) {
      if (marker.standing !== 'own' && marker.standing !== 'intel') return;
      const x = rect.x - 3;
      const y = rect.y - 3;
      const w = rect.width + 6;
      const h = rect.height + 6;
      ctx.save();
      if (marker.standing === 'own') {
        // 어두운 둘레 1px 위에 청동 2px — 밝은 평지 타일에서도 보인다
        ctx.lineWidth = 4;
        ctx.strokeStyle = INK;
        ctx.strokeRect(x, y, w, h);
        ctx.lineWidth = 2;
        ctx.strokeStyle = CORPS_OWN_STROKE;
        ctx.strokeRect(x, y, w, h);
      } else {
        // 점선 테두리도 표지와 같이 흐리게(보드) — 밝은 타일에서 사라지지 않게 어두운 점선을 밑에 깐다(캡처에서 안 보였다)
        ctx.globalAlpha *= CORPS_INTEL_ALPHA;
        ctx.setLineDash(INTEL_DASH);
        ctx.lineWidth = 3.5;
        ctx.strokeStyle = INK;
        ctx.strokeRect(x, y, w, h);
        ctx.lineWidth = 1.5;
        ctx.strokeStyle = '#ece6d8';
        ctx.strokeRect(x, y, w, h);
        ctx.setLineDash([]);
        ctx.globalAlpha = 1;
        // 「?」 표는 흐리지 않는다(색만으로 가르지 않음)
        const r = 9;
        const cx = x + w;
        const cy = y;
        ctx.beginPath();
        ctx.arc(cx, cy, r, 0, Math.PI * 2);
        ctx.fillStyle = '#ece6d8';
        ctx.fill();
        ctx.fillStyle = '#161410';
        ctx.font = `900 13px ${font}`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('?', cx, cy + 1);
      }
      ctx.restore();
    },
    drawBand(ctx, marker, text, rect) {
      ctx.save();
      if (marker.standing === 'intel') ctx.globalAlpha *= 0.75;
      ctx.fillStyle = 'rgba(12,15,14,0.78)';
      ctx.fillRect(rect.x, rect.y, rect.width, rect.height);
      ctx.fillStyle = '#f5ecd6';
      ctx.font = `700 ${CORPS_BAND_FONT_PX}px ${font}`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(text, rect.x + rect.width / 2, rect.y + rect.height / 2 + 1);
      ctx.restore();
    },
  };
}
