// 城 위에 얹는 표식 — 깃발·선택 테·전장 마름모.
//
// 두 렌더러(2D 스프라이트판·3D 겹판)가 같은 그림을 그려야 해서 한 벌만 둔다.
//
// 요점은 **화면 좌표**로 그린다는 것이다. 세계 좌표로 그리면 전체 보기(타일 13px)에서
// 표식이 1px 로 줄어 사라지고, 당기면 반대로 화면을 덮는다. 배포본 2D 판이 그랬다 —
// 「깃발이 없고 장판·관도만 강조되어 있다」는 지적이 거기서 나왔다(2026-09-09).
// 눈에 띄던 둘은 전장이었고, 전장만 표식이 커 보였던 게 아니라 城 표식이 안 보였던 것이다.

/**
 * 확대 배율 → 표식 배율. 1 … 2.4 사이로 눌러 둔다.
 * 전체 보기에서도 읽히고(하한 1), 한 타일까지 당겨도 화면을 안 덮는다(상한 2.4).
 */
export function markerScale(viewScale: number): number {
  return Math.max(1, Math.min(2.4, viewScale * 2));
}

const INK = 'rgba(12, 15, 14, 0.85)'; // --bg
const NEUTRAL = '#8e8879'; // --muted

export interface CityFlagOptions {
  /** 세력색(정규화 끝난 css 색). 중립이면 null — 회색 깃발이 선다. */
  color: string | null;
  capital: boolean;
  /** markerScale 의 결과. */
  k: number;
}

/**
 * 깃대와 깃발. (x, y) 는 깃대 **밑동**이다.
 * 반환값은 깃발 꼭대기 y — 집기 상자를 잡는 쪽이 쓴다.
 */
export function drawCityFlag(
  context: CanvasRenderingContext2D,
  x: number,
  y: number,
  { color, capital, k }: CityFlagOptions,
): number {
  const size = (capital ? 11 : 9) * k;
  const pole = size * 2;
  const top = y - pole;
  const w = size * 1.5;
  const h = size * 1.05;

  context.save();
  // 깃대. 어두운 잉크라 밝은 지형 위에서도 형태가 선다.
  context.strokeStyle = INK;
  context.lineWidth = Math.max(1.4, size * 0.18);
  context.lineCap = 'round';
  context.beginPath();
  context.moveTo(x, y);
  context.lineTo(x, top);
  context.stroke();

  // 깃발 — 제비꼬리. 사각형이면 도시명 상자와 헷갈린다.
  context.beginPath();
  context.moveTo(x, top);
  context.lineTo(x + w, top);
  context.lineTo(x + w - size * 0.42, top + h / 2);
  context.lineTo(x + w, top + h);
  context.lineTo(x, top + h);
  context.closePath();
  context.fillStyle = color ?? NEUTRAL;
  context.globalAlpha = color ? 1 : 0.7;
  context.fill();
  context.globalAlpha = 1;
  context.strokeStyle = INK;
  context.lineWidth = Math.max(1, size * 0.12);
  context.stroke();

  if (capital) {
    // 수도는 깃대 끝에 금색 구슬을 단다 — 색만으로는 못 가른다.
    context.fillStyle = '#d3b064'; // --bronze
    context.beginPath();
    context.arc(x, top, Math.max(1.8, size * 0.22), 0, Math.PI * 2);
    context.fill();
  }
  context.restore();
  return top;
}

/** 선택·주둔 표시 테. 城 이 서 있는 칸을 화면 좌표 마름모로 두른다. */
export function drawCityRing(
  context: CanvasRenderingContext2D,
  x: number,
  y: number,
  { color, k }: { color: string; k: number },
): void {
  const rx = 17 * k;
  const ry = rx / 2;
  context.save();
  context.strokeStyle = color;
  context.lineWidth = Math.max(2, 1.6 * k);
  context.beginPath();
  context.moveTo(x, y - ry);
  context.lineTo(x + rx, y);
  context.lineTo(x, y + ry);
  context.lineTo(x - rx, y);
  context.closePath();
  context.stroke();
  context.restore();
}

/** 전장 마름모. 반환값은 집기 반지름이다. */
export function drawBattlefieldMark(
  context: CanvasRenderingContext2D,
  x: number,
  y: number,
  { current, k }: { current: boolean; k: number },
): number {
  const radius = 8 * k;
  context.save();
  context.fillStyle = '#1b201d'; // --panel
  context.strokeStyle = current ? '#ffd36d' : '#d3b064'; // --focus / --bronze
  context.lineWidth = Math.max(2, 1.6 * k);
  context.beginPath();
  context.moveTo(x, y - radius);
  context.lineTo(x + radius, y);
  context.lineTo(x, y + radius);
  context.lineTo(x - radius, y);
  context.closePath();
  context.fill();
  context.stroke();
  context.restore();
  return radius + 6;
}

/** 도시명. 받침 대신 외곽선을 쓴다 — 세력색 위 검정 볼드는 판독이 어려웠다. */
export function drawCityName(
  context: CanvasRenderingContext2D,
  name: string,
  x: number,
  y: number,
  k: number,
): void {
  context.save();
  context.font = `600 ${Math.round(11 * k)}px "Pretendard Variable", Pretendard, sans-serif`;
  context.textAlign = 'center';
  context.textBaseline = 'top';
  context.lineJoin = 'round';
  context.strokeStyle = 'rgba(12, 15, 14, 0.92)';
  context.lineWidth = Math.max(2.5, 2.4 * k);
  context.strokeText(name, x, y);
  context.fillStyle = '#ece6d8'; // --text
  context.fillText(name, x, y);
  context.restore();
}

export interface LabelBox {
  x0: number;
  x1: number;
  y0: number;
  y1: number;
}

/**
 * drawCityName 이 글씨를 놓는 자리. (x, y) 는 drawCityName 에 넘긴 좌표 그대로다.
 * 한글·한자는 글자당 대략 1em 이라 측정 없이 잡는다 — measureText 를 쓰려면 context 가
 * 필요한데, 솎는 쪽은 그리기 전에 정해야 한다.
 */
export function cityLabelBox(x: number, y: number, name: string, k: number): LabelBox {
  const size = Math.round(11 * k);
  const halfWidth = (name.length * size) / 2;
  return { x0: x - halfWidth, x1: x + halfWidth, y0: y, y1: y + size };
}

/**
 * 겹치는 이름표를 솎는다. 앞엣것이 이기고 뒤엣것을 버린다 — 부르는 쪽이 순서로 우선순위를 준다.
 *
 * 한반도 남부에 城 이 12 곳(사로국·구야국·안야국·대가야·성산가야·고령가야·압독국·
 * 소문국·실직국·목지국·벽비리국·고자미동국) 몰려 있다. 다 쓰면 한 덩어리로 뭉개져
 * 아무것도 못 읽는다. 당길수록 서로 벌어져 하나씩 되살아난다 — 郡治를 당기면 縣이
 * 나오는 것과 같은 규칙이다.
 */
export function dropOverlappingLabels(boxes: readonly LabelBox[]): boolean[] {
  const keep: boolean[] = [];
  const placed: LabelBox[] = [];
  for (const box of boxes) {
    const clash = placed.some(
      (p) => box.x0 < p.x1 && box.x1 > p.x0 && box.y0 < p.y1 && box.y1 > p.y0,
    );
    keep.push(!clash);
    if (!clash) placed.push(box);
  }
  return keep;
}
