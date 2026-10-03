import { cellToScreen, viewLevel } from './camera';
import type { Camera, CellPoint, ViewLevel, Viewport } from './types';

export type LabelKind = 'ju' | 'commandery' | 'commanderySeat' | 'county' | 'pass' | 'ferry';

export interface LabelCandidate {
  id: string;
  text: string;
  kind: LabelKind;
  anchor: CellPoint;
  /** Higher places first. */
  priority: number;
  /** City footprint side in cells (label sits just below it). Defaults to 1. */
  footprintSpan?: number;
}

export interface PlacedLabel {
  id: string;
  text: string;
  kind: LabelKind;
  fontPx: number;
  /** Screen CSS px, top-left of the label box. */
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface LabelStyle { fontPx: number; bold: boolean }

/** Which kinds each view level shows, and at which size (design spec §4.2). */
export const LABEL_STYLES: Readonly<Record<ViewLevel, Readonly<Partial<Record<LabelKind, LabelStyle>>>>> = Object.freeze({
  ju: { ju: { fontPx: 18, bold: true } },
  commandery: {
    commandery: { fontPx: 15, bold: false },
    commanderySeat: { fontPx: 13, bold: false },
    pass: { fontPx: 13, bold: false },
  },
  county: {
    county: { fontPx: 15, bold: false },
    commanderySeat: { fontPx: 15, bold: false },
    pass: { fontPx: 15, bold: false },
    ferry: { fontPx: 15, bold: false },
  },
});

export const DEFAULT_LABEL_PADDING = 4;
/** Gap between a city footprint's bottom edge and its label, CSS px. */
const BELOW_FOOTPRINT_GAP = 2;

export type MeasureLabel = (text: string, fontPx: number, bold: boolean) => { width: number; height: number };

export interface LayoutLabelOptions {
  padding?: number;
  hidden?: ReadonlySet<LabelKind>;
  /** Screen boxes labels must not cover (corps markers and flags); a label hitting one is dropped. */
  avoid?: ReadonlyArray<{ x: number; y: number; width: number; height: number }>;
  /**
   * 꼭 남길 城 이름표 id 하나(내 위치 핀이 선 城 — 실지도 결함 3). 우선순위와 관계없이 맨 먼저 놓고, 발자국 아래 자리가
   * 피할 상자(내 군단 표지 · 핀)에 막히면 발자국 위 → 오른쪽 → 왼쪽 자리를 차례로 본다. 다른 이름표는 이 자리를 피한다.
   * 다른 城 이름은 그대로 발자국 아래에만 둔다(보드 §4.2).
   */
  keep?: string;
}

interface Box { x: number; y: number; width: number; height: number }

function intersects(a: Box, b: Box): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

function compareCandidates(a: LabelCandidate, b: LabelCandidate): number {
  if (a.priority !== b.priority) return b.priority - a.priority;
  // localeCompare 는 환경마다 순서가 달라질 수 있어 코드 단위 비교를 쓴다.
  if (a.id < b.id) return -1;
  if (a.id > b.id) return 1;
  return 0;
}

/**
 * Greedy label placement for the current view level: `keep` first, then priority desc, then id asc.
 * A label is dropped when its padded box hits a placed box or it lies fully outside the viewport.
 * Region names (州 · 郡) that straddle the viewport edge are moved inside; point names (城 · 관 · 나루) keep their spot.
 */
export function layoutLabels(
  candidates: ReadonlyArray<LabelCandidate>,
  cam: Camera,
  viewport: Viewport,
  measure: MeasureLabel,
  options: LayoutLabelOptions = {},
): PlacedLabel[] {
  const padding = options.padding ?? DEFAULT_LABEL_PADDING;
  const styles = LABEL_STYLES[viewLevel(cam.zoom)];
  const keep = options.keep;
  const visible = candidates
    .filter((candidate) => styles[candidate.kind] && !options.hidden?.has(candidate.kind))
    .sort((a, b) => (a.id === keep ? -1 : b.id === keep ? 1 : compareCandidates(a, b)));

  const placed: PlacedLabel[] = [];
  for (const candidate of visible) {
    const style = styles[candidate.kind] as LabelStyle;
    const { width, height } = measure(candidate.text, style.fontPx, style.bold);
    const center = cellToScreen(
      { col: candidate.anchor.col + 0.5, row: candidate.anchor.row + 0.5 },
      cam,
      viewport,
    );
    const region = candidate.kind === 'ju' || candidate.kind === 'commandery';
    const half = ((candidate.footprintSpan ?? 1) / 2) * cam.zoom;
    // 州 · 郡 이름은 자리 가운데, 나머지는 城 발자국 바로 아래에 둔다. 꼭 남길 城 이름만 막히면 위 · 오른쪽 · 왼쪽도 본다.
    const spots = region
      ? [{ x: center.x - width / 2, y: center.y - height / 2 }]
      : [
        { x: center.x - width / 2, y: center.y + half + BELOW_FOOTPRINT_GAP },
        ...(candidate.id === keep ? [
          { x: center.x - width / 2, y: center.y - half - BELOW_FOOTPRINT_GAP - height },
          { x: center.x + half + BELOW_FOOTPRINT_GAP, y: center.y - height / 2 },
          { x: center.x - half - BELOW_FOOTPRINT_GAP - width, y: center.y - height / 2 },
        ] : []),
      ];
    let spot: { x: number; y: number } | null = null;
    for (const at of spots) {
      let { x, y } = at;
      if (x + width <= 0 || x >= viewport.width || y + height <= 0 || y >= viewport.height) continue;
      // 화면 끝에 걸친 州 · 郡 이름은 화면 안으로 들인다 — 넓은 구역이라 조금 옮겨도 같은 구역을 가리킨다(화면을 채우는 배경 지도에서
      // 「유주」 · 「동이」가 끝에 걸려 일부만 보였다, K5 · K10 10-03). 城 · 관 · 나루 이름은 그 칸을 가리키므로 옮기지 않는다.
      if (region && width <= viewport.width && height <= viewport.height) {
        x = Math.min(Math.max(x, 0), viewport.width - width);
        y = Math.min(Math.max(y, 0), viewport.height - height);
      }
      const padded: Box = { x: x - padding, y: y - padding, width: width + padding * 2, height: height + padding * 2 };
      if (placed.some((other) => intersects(padded, other))) continue;
      if (options.avoid?.some((box) => intersects(padded, box))) continue;
      spot = { x, y };
      break;
    }
    if (!spot) continue;
    const { x, y } = spot;
    placed.push({
      id: candidate.id,
      text: candidate.text,
      kind: candidate.kind,
      fontPx: style.fontPx,
      x,
      y,
      width,
      height,
    });
  }
  return placed;
}
