// 게임 로그 토큰(devsam 색/태그 마크업) 파서 — 저장·와이어 계약은 그대로 두고(엔진 골든이 바이트 비교) 렌더만 바꾼다.
// 문법(legacy hwe/ts/utilGame/formatLog.ts 와 동일 정규식):
//   <R><B><G><M><C><L><S><O><D><Y><W> 색 · <1> 작은 글씨 · <X1> 색+작은 글씨 · </> 닫기
//   통과 HTML: <b> </b> · <span class='ev_failed'> · <span style='color:#hex'> · </span>
// 결과는 innerHTML 이 아니라 세그먼트 배열이라 XSS 표면이 없다(모르는 태그는 글자 그대로 남는다).

export type LogTone = 'R' | 'B' | 'G' | 'M' | 'C' | 'L' | 'S' | 'O' | 'D' | 'Y' | 'W';

export interface LogSegment {
  readonly text: string;
  readonly tone?: LogTone;
  /** <span style='color:#hex'> — 명시 색은 토큰 색보다 우선. */
  readonly color?: string;
  readonly small?: boolean;
  readonly bold?: boolean;
  readonly failed?: boolean;
}

interface Frame {
  tone?: LogTone;
  color?: string;
  small?: boolean;
  bold?: boolean;
  failed?: boolean;
}

const TOKEN = /<([RBGMCLSODYW]1?|1|\/)>|<\/?b>|<span class=(['"])ev_failed\2>|<span style=(['"])color:\s*#[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{3})?;?\3>|<\/span>/g;
const HEX = /^<span style=(['"])color:\s*(#[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{3})?);?\1>$/;

function frameOf(tag: string, sub: string | undefined): Frame | 'close' | null {
  if (sub === '/') return 'close';
  if (sub === '1') return { small: true };
  if (sub) return sub.length === 2 ? { tone: sub[0] as LogTone, small: true } : { tone: sub as LogTone };
  if (tag === '<b>') return { bold: true };
  if (tag === '</b>' || tag === '</span>') return 'close';
  if (tag === "<span class='ev_failed'>" || tag === '<span class="ev_failed">') return { failed: true };
  const color = HEX.exec(tag)?.[2];
  if (color) return { color };
  return null;
}

function effective(stack: readonly Frame[]): Omit<LogSegment, 'text'> {
  const out: { tone?: LogTone; color?: string; small?: boolean; bold?: boolean; failed?: boolean } = {};
  for (const f of stack) {
    if (f.tone) { out.tone = f.tone; out.color = undefined; }
    if (f.color) { out.color = f.color; out.tone = undefined; }
    if (f.small) out.small = true;
    if (f.bold) out.bold = true;
    if (f.failed) out.failed = true;
  }
  return out;
}

/** 토큰 문자열을 스타일 세그먼트로 편다. 닫기 초과는 무시, 열린 채 끝나면 끝에서 닫는다(브라우저와 같다). */
export function parseLogTokens(text: string | null | undefined): LogSegment[] {
  if (!text) return [];
  const segments: LogSegment[] = [];
  const stack: Frame[] = [];
  let last = 0;
  const push = (chunk: string) => {
    if (!chunk) return;
    const style = effective(stack);
    const prev = segments[segments.length - 1];
    if (prev && prev.tone === style.tone && prev.color === style.color && !!prev.small === !!style.small
      && !!prev.bold === !!style.bold && !!prev.failed === !!style.failed) {
      segments[segments.length - 1] = { ...prev, text: prev.text + chunk };
    } else {
      segments.push({ text: chunk, ...style });
    }
  };
  TOKEN.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = TOKEN.exec(text)) !== null) {
    push(text.slice(last, m.index));
    const frame = frameOf(m[0], m[1]);
    if (frame === 'close') stack.pop();
    else if (frame) stack.push(frame);
    else push(m[0]);
    last = m.index + m[0].length;
  }
  push(text.slice(last));
  return segments;
}

/** 토큰을 벗긴 평문(접근성 이름·검색·테스트용). */
export function logPlainText(text: string | null | undefined): string {
  return parseLogTokens(text).map((s) => s.text).join('');
}
