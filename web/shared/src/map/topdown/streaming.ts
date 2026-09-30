// 조각 올리기 계획: 받은 조각은 GPU에 없거나(슬롯에서 밀려났거나) 자료가 바뀌었으면 다시 올린다.
// GPU 슬롯과 로더 캐시는 따로 밀어내므로 「올렸다」 장부만 믿으면 밀려난 조각이 영영 안 돌아온다.
import { chunkKey, type ChunkCoord } from './chunks';
import type { ChunkData } from './types';

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
}

export function planChunks(wanted: readonly ChunkCoord[], state: ChunkUploadState): ChunkPlan {
  const plan: ChunkPlan = { upload: [], request: [] };
  for (const { cx, cy } of wanted) {
    const key = chunkKey(cx, cy);
    const data = state.peek(cx, cy);
    if (!data) {
      plan.request.push({ cx, cy });
      continue;
    }
    if (!state.onGpu(cx, cy) || state.uploaded(key) !== data) plan.upload.push({ cx, cy, key, data });
  }
  return plan;
}
