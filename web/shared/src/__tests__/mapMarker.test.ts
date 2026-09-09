// 지도 표식은 **화면 좌표**로 그린다. 여기서 지키는 것은 그 두 성질이다.
//   1) 표식 배율은 확대율을 따라가되 위아래로 눌려 있다 — 전체 보기에서 사라지지 않고,
//      한 칸까지 당겨도 화면을 덮지 않는다(배포본은 세계 좌표라 둘 다 어겼다).
//   2) 세력색은 곱해도 땅을 어둡게 만들지 않는다.
import { describe, expect, it, vi } from 'vitest';
import {
  cityLabelBox,
  drawCityFlag,
  drawCityName,
  dropOverlappingLabels,
  luminancePreserving,
  markerScale,
  normaliseNationColor,
  parseHex,
} from '../index';

/** 캔버스 대신 호출만 받아 적는 가짜. 좌표 계산만 검사하므로 그림은 필요 없다. */
function fakeContext() {
  const calls: [string, unknown[]][] = [];
  const record = (name: string) => (...args: unknown[]) => { calls.push([name, args]); };
  const context = {
    calls,
    save: record('save'),
    restore: record('restore'),
    beginPath: record('beginPath'),
    closePath: record('closePath'),
    moveTo: record('moveTo'),
    lineTo: record('lineTo'),
    arc: record('arc'),
    fill: record('fill'),
    stroke: record('stroke'),
    // 글자는 그 순간의 fillStyle 까지 같이 적는다 — 흐린 글씨인지가 검사 대상이다.
    fillText: (...args: unknown[]) => { calls.push(['fillText', [...args, context.fillStyle]]); },
    strokeText: record('strokeText'),
    font: '' as string,
    textAlign: '' as CanvasTextAlign,
    textBaseline: '' as CanvasTextBaseline,
    lineJoin: '' as CanvasLineJoin,
    fillStyle: '' as string,
    strokeStyle: '' as string,
    lineWidth: 0,
    lineCap: '' as CanvasLineCap,
    globalAlpha: 1,
  };
  return context as unknown as CanvasRenderingContext2D & { calls: [string, unknown[]][] };
}

describe('markerScale', () => {
  it('전체 보기에서도 1 아래로 내려가지 않는다', () => {
    // 타일 13px = 배율 0.05. 세계 좌표로 그리면 표식이 1px 이 되던 자리다.
    expect(markerScale(0.05)).toBe(1);
    expect(markerScale(0)).toBe(1);
  });

  it('당겨도 2.4 를 넘지 않는다', () => {
    expect(markerScale(2)).toBe(2.4);
    expect(markerScale(50)).toBe(2.4);
  });

  it('그 사이에서는 배율의 두 배다', () => {
    expect(markerScale(0.7)).toBeCloseTo(1.4, 6);
  });
});

describe('drawCityFlag', () => {
  it('깃발 꼭대기를 돌려준다 — 집기 상자가 그 값으로 잡힌다', () => {
    const context = fakeContext();
    // 수도가 아니면 size = 9k, 깃대 길이 = 2 * size.
    expect(drawCityFlag(context, 100, 200, { color: '#c0392b', capital: false, k: 1 }))
      .toBe(200 - 18);
    // 수도는 size = 11k 라 더 높다.
    expect(drawCityFlag(context, 100, 200, { color: '#c0392b', capital: true, k: 2 }))
      .toBe(200 - 44);
  });

  it('수도만 깃대 끝에 구슬을 단다', () => {
    const plain = fakeContext();
    drawCityFlag(plain, 0, 0, { color: '#c0392b', capital: false, k: 1 });
    expect(plain.calls.filter(([name]) => name === 'arc')).toHaveLength(0);

    const capital = fakeContext();
    drawCityFlag(capital, 0, 0, { color: '#c0392b', capital: true, k: 1 });
    expect(capital.calls.filter(([name]) => name === 'arc')).toHaveLength(1);
  });

  it('중립은 회색 깃발이 서고, 그래도 깃발은 선다', () => {
    const context = fakeContext();
    const spy = vi.spyOn(context, 'fill');
    drawCityFlag(context, 0, 0, { color: null, capital: false, k: 1 });
    expect(spy).toHaveBeenCalled();
    // 제비꼬리 5 점 — 깃대(moveTo/lineTo 한 쌍)와 별개다.
    expect(context.calls.filter(([name]) => name === 'lineTo')).toHaveLength(5);
  });
});

describe('luminancePreserving', () => {
  const luma = ({ r, g, b }: { r: number; g: number; b: number }) => (
    0.2126 * r + 0.7152 * g + 0.0722 * b
  );

  it('밝기를 올려 준다 — 곱해도 땅이 어두워지지 않는다', () => {
    for (const hex of ['#0000ff', '#ff0000', '#00ff00', '#6b3fa0', '#123456']) {
      const before = normaliseNationColor(hex);
      const after = luminancePreserving(before);
      expect(luma(after)).toBeGreaterThan(luma(before));
      // 곱수는 1.7 로 눌려 있다 — 그 위로 가면 밝은 채널이 포화해 색상이 틀어진다.
      expect(Math.max(after.r, after.g, after.b)).toBeLessThanOrEqual(1.7 + 1e-9);
    }
  });

  it('색상 비율은 그대로다 — 나라 구분이 남는다', () => {
    const before = normaliseNationColor('#0000ff');
    const after = luminancePreserving(before);
    expect(after.b / after.r).toBeCloseTo(before.b / before.r, 6);
    expect(after.g / after.r).toBeCloseTo(before.g / before.r, 6);
  });

  it('검정은 흰색으로 떨어진다 — 0 을 곱해 땅을 지우지 않는다', () => {
    expect(luminancePreserving(parseHex('#000000')!)).toEqual({ r: 1, g: 1, b: 1 });
  });
});


describe('drawCityName', () => {
  it('--text 로 쓰고 외곽선을 두른다 — 세력색 위에서도 읽혀야 한다', () => {
    const context = fakeContext();
    drawCityName(context, '낙양', 100, 200, 1);
    const [, fill] = context.calls.find(([name]) => name === 'fillText')!;
    const [, stroke] = context.calls.find(([name]) => name === 'strokeText')!;
    expect(fill).toEqual(['낙양', 100, 200, '#ece6d8']);
    expect(stroke).toEqual(['낙양', 100, 200]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 이름표 솎기. 한반도 남부에 城 이 12 곳 몰려 있어서 다 쓰면 한 덩어리로 뭉개진다.
// 郡國 밖 세력(백제국·사로국·야마일국 …)도 이제 그냥 城 이라 같은 규칙을 받는다 —
// 예전에는 속 빈 마름모를 따로 그리고 그쪽에만 솎기를 걸었고, 그 31 곳은 게임 城 이라
// 아이콘까지 같이 서서 한 자리에 두 그림이 겹쳐 있었다.
describe('cityLabelBox / dropOverlappingLabels', () => {
  it('상자는 넘긴 좌표에서 시작하고 글자 수를 따라 넓어진다', () => {
    // k = 1 → 글자 11px. drawCityName 이 textBaseline 'top' 으로 그리는 자리 그대로다.
    expect(cityLabelBox(100, 200, '백제국', 1))
      .toEqual({ x0: 83.5, x1: 116.5, y0: 200, y1: 211 });
    expect(cityLabelBox(100, 200, '예', 1))
      .toEqual({ x0: 94.5, x1: 105.5, y0: 200, y1: 211 });
  });

  it('겹치면 앞엣것만 남긴다 — 부르는 쪽이 순서로 우선순위를 준다', () => {
    const boxes = [
      cityLabelBox(100, 200, '사로국', 1),
      cityLabelBox(104, 201, '구야국', 1), // 4px 옆 — 겹친다
      cityLabelBox(300, 200, '야마일국', 1), // 멀다
    ];
    expect(dropOverlappingLabels(boxes)).toEqual([true, false, true]);
  });

  it('벌어지면 버렸던 것이 되살아난다 — 당길수록 하나씩 나온다', () => {
    const boxes = [
      cityLabelBox(100, 200, '사로국', 1),
      cityLabelBox(160, 201, '구야국', 1),
    ];
    expect(dropOverlappingLabels(boxes)).toEqual([true, true]);
  });
});
