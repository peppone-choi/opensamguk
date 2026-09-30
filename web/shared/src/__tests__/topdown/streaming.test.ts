import { describe, expect, it } from 'vitest';
import { planChunks } from '../../map/topdown/streaming';
import { chunksToStream } from '../../map/topdown/streaming';
import type { ChunkData } from '../../map/topdown/types';

const data = (): ChunkData => ({ tiles: new Uint16Array(4), provinces: new Uint16Array(4) });

describe('조각 올리기 계획', () => {
  it('없는 조각은 받기를 요청하고, 받은 조각은 처음 한 번 올린다', () => {
    const a = data();
    const plan = planChunks([{ cx: 0, cy: 0 }, { cx: 1, cy: 0 }], {
      peek: (cx) => (cx === 0 ? a : undefined),
      onGpu: () => false,
      uploaded: () => undefined,
    });
    expect(plan.upload.map((u) => u.key)).toEqual(['0_0']);
    expect(plan.request).toEqual([{ cx: 1, cy: 0 }]);
  });

  it('이미 GPU에 있고 같은 자료면 다시 올리지 않는다', () => {
    const a = data();
    const plan = planChunks([{ cx: 0, cy: 0 }], { peek: () => a, onGpu: () => true, uploaded: () => a });
    expect(plan.upload).toEqual([]);
  });

  it('GPU 슬롯에서 밀려난 조각은 같은 자료여도 다시 올린다', () => {
    const a = data();
    const plan = planChunks([{ cx: 2, cy: 3 }], { peek: () => a, onGpu: () => false, uploaded: () => a });
    expect(plan.upload.map((u) => u.key)).toEqual(['2_3']);
  });

  it('한 프레임에 올릴 수를 넘으면 앞(가까운) 조각부터 올리고 다음 프레임을 부탁한다', () => {
    const coords = [0, 1, 2, 3, 4, 5].map((cx) => ({ cx, cy: 0 }));
    const plan = planChunks(coords, { peek: () => data(), onGpu: () => false, uploaded: () => undefined }, 4);
    expect(plan.upload.map((u) => u.cx)).toEqual([0, 1, 2, 3]);
    expect(plan.more).toBe(true);
    expect(planChunks(coords.slice(0, 4), { peek: () => data(), onGpu: () => false, uploaded: () => undefined }, 4).more).toBe(false);
  });

  it('로더가 새로 받은 자료(다른 객체)는 다시 올린다', () => {
    const plan = planChunks([{ cx: 0, cy: 0 }], { peek: () => data(), onGpu: () => true, uploaded: () => data() });
    expect(plan.upload).toHaveLength(1);
  });
});

describe('보이는 조각 먼저', () => {
  const shape = { cols: 1024, rows: 1024 };
  const rect = { col0: 300, row0: 300, col1: 380, row1: 350 }; // 조각 256칸: 보이는 것은 (1, 1) 하나
  it('보이는 조각이 다 오기 전에는 둘레 한 칸을 받지 않는다', () => {
    const first = chunksToStream(rect, 256, shape, () => false);
    expect(first.wanted).toEqual([{ cx: 1, cy: 1 }]);
    expect(first.visible).toEqual([{ cx: 1, cy: 1 }]);
  });
  it('보이는 조각이 다 오면 둘레 한 칸까지', () => {
    const next = chunksToStream(rect, 256, shape, (cx, cy) => cx === 1 && cy === 1);
    expect(next.wanted).toHaveLength(9);
    expect(next.wanted[0]).toEqual({ cx: 1, cy: 1 });
  });
});
