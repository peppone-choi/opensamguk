import { describe, expect, it } from 'vitest';
import { layoutLabels, type LabelCandidate, type LabelKind } from '../../map/topdown/labels';
import type { Camera, Viewport } from '../../map/topdown/types';

const viewport: Viewport = { width: 800, height: 600, dpr: 1 };
/** Each character is fontPx wide; height is the font size. */
const measure = (text: string, fontPx: number) => ({ width: text.length * fontPx, height: fontPx });

const candidate = (
  id: string, kind: LabelKind, col: number, row: number, priority = 0, footprintSpan?: number,
): LabelCandidate => ({ id, text: id, kind, anchor: { col, row }, priority, footprintSpan });

const kinds = (placed: { kind: LabelKind }[]) => [...new Set(placed.map((label) => label.kind))].sort();

describe('보기 수준별 이름', () => {
  // 서로 멀리 떨어뜨려 겹침 없이 종류만 본다.
  const all = [
    candidate('J', 'ju', 100, 100),
    candidate('C', 'commandery', 110, 100),
    candidate('S', 'commanderySeat', 100, 110),
    candidate('X', 'county', 110, 110),
    candidate('P', 'pass', 90, 90),
    candidate('F', 'ferry', 90, 110),
  ];
  const camAt = (zoom: number): Camera => ({ center: { col: 100, row: 100 }, zoom });

  it('州 보기는 州 이름만 18px', () => {
    const placed = layoutLabels(all, camAt(1), viewport, measure);
    expect(kinds(placed)).toEqual(['ju']);
    expect(placed[0].fontPx).toBe(18);
  });

  it('郡 보기는 郡 15px + 치소 · 관 13px', () => {
    const placed = layoutLabels(all, camAt(4), viewport, measure);
    expect(kinds(placed)).toEqual(['commandery', 'commanderySeat', 'pass']);
    const font = Object.fromEntries(placed.map((label) => [label.kind, label.fontPx]));
    expect(font).toEqual({ commandery: 15, commanderySeat: 13, pass: 13 });
  });

  it('縣 보기는 縣 · 치소 · 관 · 나루 모두 15px', () => {
    const placed = layoutLabels(all, camAt(16), viewport, measure);
    expect(kinds(placed)).toEqual(['commanderySeat', 'county', 'ferry', 'pass']);
    expect(new Set(placed.map((label) => label.fontPx))).toEqual(new Set([15]));
  });

  it('숨긴 종류는 빠진다(「도시 이름」 끄기)', () => {
    const placed = layoutLabels(all, camAt(16), viewport, measure, { hidden: new Set<LabelKind>(['county', 'commanderySeat']) });
    expect(kinds(placed)).toEqual(['ferry', 'pass']);
  });

  it('州 이름은 굵게 재도록 measure 에 알린다', () => {
    const calls: [string, number, boolean][] = [];
    layoutLabels(all, camAt(1), viewport, (text, px, bold) => { calls.push([text, px, bold]); return measure(text, px); });
    expect(calls).toEqual([['J', 18, true]]);
  });
});

describe('자리', () => {
  it('縣 이름은 발자국 아래 가운데, 郡 이름은 자리 가운데', () => {
    const cam: Camera = { center: { col: 50, row: 50 }, zoom: 16 };
    const [county] = layoutLabels([candidate('AB', 'county', 50, 50, 0, 5)], cam, viewport, measure);
    // 칸 가운데 x = 400 + 8 = 408, 너비 30 → x 393. 아래: 300 + 8 + 5/2 × 16 + 2 = 350.
    expect(county).toMatchObject({ x: 393, y: 350, width: 30, height: 15 });
    const [plain] = layoutLabels([candidate('AB', 'county', 50, 50)], cam, viewport, measure);
    expect(plain.y).toBe(300 + 8 + 8 + 2);
    const commanderyCam: Camera = { ...cam, zoom: 4 };
    const [commandery] = layoutLabels([candidate('AB', 'commandery', 50, 50)], commanderyCam, viewport, measure);
    // 가운데 (402, 302), 15px 두 글자 → 30×15.
    expect(commandery).toMatchObject({ x: 387, y: 294.5, width: 30, height: 15 });
  });
});

describe('겹침 · 화면 밖', () => {
  const cam: Camera = { center: { col: 50, row: 50 }, zoom: 16 };

  it('겹치면 우선순위 낮은 쪽이 빠진다', () => {
    const placed = layoutLabels([
      candidate('low', 'county', 50, 50, 1),
      candidate('high', 'county', 51, 50, 9),
      candidate('far', 'county', 60, 50, 0),
    ], cam, viewport, measure);
    expect(placed.map((label) => label.id)).toEqual(['high', 'far']);
  });

  it('여백(4px) 안으로 들어와도 겹침이다. 여백을 0 으로 두면 붙어 놓인다', () => {
    // 너비 15 두 장, 칸 가운데가 16px 떨어져 1px 틈 → 여백 4 에 걸린다.
    const pair = [candidate('A', 'county', 50, 50, 2), candidate('B', 'county', 51, 50, 1)];
    expect(layoutLabels(pair, cam, viewport, measure).map((label) => label.id)).toEqual(['A']);
    expect(layoutLabels(pair, cam, viewport, measure, { padding: 0 }).map((label) => label.id)).toEqual(['A', 'B']);
  });

  it('같은 우선순위는 id 순, 입력 순서를 섞어도 결과가 같다', () => {
    const input: LabelCandidate[] = [];
    for (let i = 0; i < 40; i += 1) {
      input.push(candidate(`c${String(i).padStart(2, '0')}`, 'county', 30 + (i % 10) * 2, 35 + Math.floor(i / 10) * 3, i % 3));
    }
    const expected = layoutLabels(input, cam, viewport, measure);
    expect(expected.length).toBeGreaterThan(5);
    expect(expected.length).toBeLessThan(40);
    let seed = 7;
    for (let round = 0; round < 5; round += 1) {
      const shuffled = [...input];
      for (let i = shuffled.length - 1; i > 0; i -= 1) {
        seed = (seed * 1103515245 + 12345) % 2147483648;
        const j = seed % (i + 1);
        [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
      }
      expect(layoutLabels(shuffled, cam, viewport, measure)).toEqual(expected);
    }
    const tie = layoutLabels([candidate('b', 'county', 50, 50, 1), candidate('a', 'county', 50, 50, 1)], cam, viewport, measure);
    expect(tie.map((label) => label.id)).toEqual(['a']);
  });

  it('화면 밖 이름은 버리고 자리를 차지하지 않는다', () => {
    const placed = layoutLabels([
      candidate('offscreen', 'county', 200, 50, 99),
      candidate('above', 'county', 50, 20, 98),
      candidate('onscreen', 'county', 50, 50, 0),
    ], cam, viewport, measure);
    expect(placed.map((label) => label.id)).toEqual(['onscreen']);
    // 반쯤 걸친 이름은 남는다.
    const edge = layoutLabels([candidate('EDGE', 'county', 25, 50, 0)], cam, viewport, measure);
    expect(edge).toHaveLength(1);
    expect(edge[0].x).toBeLessThan(0);
  });
});

describe('화면 끝에 걸친 구역 이름(州 · 郡)', () => {
  it('州 · 郡 이름은 화면 안으로 들이고, 城 이름은 제자리에 둔다(걸쳐도 남는다)', () => {
    const ju: Camera = { center: { col: 50, row: 50 }, zoom: 1 };
    // 州 이름 「유주」(18px 두 글자 → 36×18)의 자리 가운데가 화면 위 끝(y = 0.5) — 그대로면 위로 9px 넘친다
    const [top] = layoutLabels([candidate('유주', 'ju', 50, -250)], ju, viewport, measure);
    expect(top).toMatchObject({ y: 0, height: 18 });
    // 오른쪽 끝(x 가운데 = 800.5) — 안으로 들어와 오른쪽 끝에 붙는다
    const [right] = layoutLabels([candidate('동이', 'ju', 450, 50)], ju, viewport, measure);
    expect(right.x + right.width).toBe(800);
    expect(right.x).toBeGreaterThanOrEqual(0);
    // 郡 이름도 같다(郡 보기 15px)
    const commandery: Camera = { center: { col: 50, row: 50 }, zoom: 4 };
    const [left] = layoutLabels([candidate('AB', 'commandery', -50, 50)], commandery, viewport, measure);
    expect(left.x).toBe(0);
    // 縣 이름은 옮기지 않는다 — 걸친 채 남는다(그 칸을 가리키므로)
    const county: Camera = { center: { col: 50, row: 50 }, zoom: 16 };
    const [edge] = layoutLabels([candidate('EDGE', 'county', 25, 50)], county, viewport, measure);
    expect(edge.x).toBeLessThan(0);
  });

  it('안으로 들인 자리에서도 다른 이름 · 피할 상자와 겹치면 빠진다', () => {
    const ju: Camera = { center: { col: 50, row: 50 }, zoom: 1 };
    const placed = layoutLabels([candidate('유주', 'ju', 50, -250)], ju, viewport, measure, { avoid: [{ x: 300, y: 0, width: 200, height: 40 }] });
    expect(placed).toEqual([]);
    // 화면에서 다 벗어난 구역 이름은 들이지 않고 버린다
    expect(layoutLabels([candidate('먼곳', 'ju', 50, -400)], ju, viewport, measure)).toEqual([]);
  });
});

describe('부대 표지 피하기', () => {
  it('부대 몸통 · 깃발 상자에 닿는 이름은 빼고, 떨어진 이름은 둔다', () => {
    const cam: Camera = { center: { col: 100, row: 100 }, zoom: 16 };
    const near = candidate('가까운縣', 'county', 100, 100);
    const far = candidate('먼縣', 'county', 110, 104);
    const free = layoutLabels([near, far], cam, viewport, measure);
    expect(free.map((l) => l.id).sort()).toEqual(['가까운縣', '먼縣']);
    const box = free.find((l) => l.id === '가까운縣')!;
    const placed = layoutLabels([near, far], cam, viewport, measure, { avoid: [{ x: box.x + 4, y: box.y + 2, width: 8, height: 8 }] });
    expect(placed.map((l) => l.id)).toEqual(['먼縣']);
  });
});
