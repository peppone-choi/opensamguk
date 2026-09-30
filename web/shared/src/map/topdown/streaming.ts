// 조각 올리기 계획: 받은 조각은 GPU에 없거나(슬롯에서 밀려났거나) 자료가 바뀌었으면 다시 올린다.
// GPU 슬롯과 로더 캐시는 따로 밀어내므로 「올렸다」 장부만 믿으면 밀려난 조각이 영영 안 돌아온다.
import { chunkKey, chunksForRect, type ChunkCoord } from './chunks';
import type { CellRect, ChunkData, MapShape } from './types';

export interface ChunkUploadState {
  /** Decoded chunk if the loader has it (no fetch). */
  peek(cx: number, cy: number): ChunkData | undefined;
  /** Whether the GPU chunk table currently holds this chunk. */
  onGpu(cx: number, cy: number): boolean;
  /** The data last uploaded for this key, if any. */
  uploaded(key: string): ChunkData | undefined;
}

export interface ChunkPlan {
  upload: { cx: number; cy: number; key: string; data: ChunkData }[];
  request: ChunkCoord[];
  /** More chunks were ready than `limit`; ask for another frame. */
  more: boolean;
}

/** `wanted` is nearest first, so a `limit` keeps the visible centre first. */
export function planChunks(wanted: readonly ChunkCoord[], state: ChunkUploadState, limit = Infinity): ChunkPlan {
  const plan: ChunkPlan = { upload: [], request: [], more: false };
  for (const { cx, cy } of wanted) {
    const key = chunkKey(cx, cy);
    const data = state.peek(cx, cy);
    if (!data) {
      plan.request.push({ cx, cy });
      continue;
    }
    if (!state.onGpu(cx, cy) || state.uploaded(key) !== data) {
      if (plan.upload.length < limit) plan.upload.push({ cx, cy, key, data });
      else plan.more = true;
    }
  }
  return plan;
}

/**
 * Which chunks to fetch and keep: the visible ones first, and the one-chunk ring around them only once every
 * visible chunk has arrived, so the ring never competes with the first picture.
 */
export function chunksToStream(
  rect: CellRect,
  chunkSize: number,
  shape: MapShape,
  loaded: (cx: number, cy: number) => boolean,
): { wanted: ChunkCoord[]; visible: ChunkCoord[] } {
  const visible = chunksForRect(rect, chunkSize, shape, 0);
  const ready = visible.every(({ cx, cy }) => loaded(cx, cy));
  return { wanted: ready ? chunksForRect(rect, chunkSize, shape, 1) : visible, visible };
}
