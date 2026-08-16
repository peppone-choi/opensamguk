// OPENSAM-41 [G0-C] G0C-k 프레임 텔레메트리 — Playwright가 읽는 값의 정의.
//
// 렌더러가 이 값을 `window.__v2Space`에 노출하고 e2e가 실측한다. 수치를
// 만들어내는 곳이 한 군데뿐이어야 "실측"이라고 말할 수 있다.

import type { RenderMode, RuntimeLod } from './contracts';

export interface FrameTelemetry {
  /** 측정 창 안의 프레임 수. */
  readonly frames: number;
  readonly elapsedMs: number;
  readonly fps: number;
  readonly p50FrameMs: number;
  readonly p95FrameMs: number;
  readonly worstFrameMs: number;
}

export interface SpaceProbe {
  readonly mode: RenderMode;
  readonly lod: RuntimeLod;
  readonly placeCount: number;
  readonly drawnMarkerCount: number;
  readonly clusterCount: number;
  readonly projectionVersion: string;
  readonly telemetry: FrameTelemetry | null;
  readonly lastPickedPlaceId: string | null;
}

function percentile(sorted: readonly number[], q: number): number {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1));
  return sorted[idx];
}

/**
 * 프레임 간격 목록 → 통계. 첫 프레임 간격은 rAF 워밍업이라 호출 측에서 제외한다.
 */
export function summarizeFrames(deltasMs: readonly number[]): FrameTelemetry {
  const elapsedMs = deltasMs.reduce((s, d) => s + d, 0);
  const sorted = [...deltasMs].sort((a, b) => a - b);
  return {
    frames: deltasMs.length,
    elapsedMs,
    fps: elapsedMs > 0 ? (deltasMs.length * 1000) / elapsedMs : 0,
    p50FrameMs: percentile(sorted, 0.5),
    p95FrameMs: percentile(sorted, 0.95),
    worstFrameMs: sorted.length ? sorted[sorted.length - 1] : 0,
  };
}

/**
 * 롤링 창으로 프레임을 모으는 수집기.
 *
 * 첫 `warmupFrames`는 버린다. 페이지 로드 직후에는 셰이더 컴파일·첫 업로드·레이아웃으로
 * 100ms대 프레임이 섞이는데, 이를 포함하면 평균 FPS가 정상 렌더 성능이 아니라 기동 비용을
 * 측정하게 된다. 버리는 건 워밍업 구간뿐이고 정상 구간의 스파이크는 그대로 남는다.
 */
export class FrameCollector {
  private readonly deltas: number[] = [];
  private last: number | null = null;
  private seen = 0;

  constructor(
    private readonly windowSize = 240,
    private readonly warmupFrames = 30,
  ) {}

  tick(nowMs: number): void {
    if (this.last !== null) {
      this.seen += 1;
      if (this.seen > this.warmupFrames) {
        this.deltas.push(nowMs - this.last);
        if (this.deltas.length > this.windowSize) this.deltas.shift();
      }
    }
    this.last = nowMs;
  }

  reset(): void {
    this.deltas.length = 0;
    this.last = null;
    this.seen = 0;
  }

  summary(): FrameTelemetry | null {
    // 표본이 너무 적으면 FPS를 "모른다"고 말한다 — 지어내지 않는다.
    return this.deltas.length >= 30 ? summarizeFrames(this.deltas) : null;
  }
}
