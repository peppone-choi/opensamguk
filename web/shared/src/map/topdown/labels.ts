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
 * Greedy label placement for the current view level: priority desc, then id asc.
 * A label is dropped when its padded box hits a placed box or it lies fully outside the viewport.
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
  const visible = candidates
    .filter((candidate) => styles[candidate.kind] && !options.hidden?.has(candidate.kind))
    .sort(compareCandidates);

  const placed: PlacedLabel[] = [];
  for (const candidate of visible) {
    const style = styles[candidate.kind] as LabelStyle;
    const { width, height } = measure(candidate.text, style.fontPx, style.bold);
    const center = cellToScreen(
      { col: candidate.anchor.col + 0.5, row: candidate.anchor.row + 0.5 },
      cam,
      viewport,
    );
    const x = center.x - width / 2;
    // 州 · 郡 이름은 자리 가운데, 나머지는 城 발자국 바로 아래에 둔다.
    const y = candidate.kind === 'ju' || candidate.kind === 'commandery'
      ? center.y - height / 2
      : center.y + ((candidate.footprintSpan ?? 1) / 2) * cam.zoom + BELOW_FOOTPRINT_GAP;

    if (x + width <= 0 || x >= viewport.width || y + height <= 0 || y >= viewport.height) continue;
    const padded: Box = {
      x: x - padding,
      y: y - padding,
      width: width + padding * 2,
      height: height + padding * 2,
    };
    if (placed.some((other) => intersects(padded, other))) continue;
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
