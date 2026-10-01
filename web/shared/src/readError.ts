/**
 * 서버 읽기 실패를 사용자 문구로 바꾼다(공용 규칙, K0 2026-10-01). 들어오는 값은 web/game `lib/api.ts` 가 던진 문자열 —
 * 「503: Service Unavailable」 같은 상태 원문, 서버가 준 상세, 브라우저 네트워크 오류(「Failed to fetch」)다.
 *
 * - 화면에는 쉬운 한국어 한 문장만 보인다. 상태 코드 · 영어 원문은 보이지 않는다 — 코드는 `code` 로만 넘긴다
 *   (StatusView `errorCode` · 접힌 상세 · 콘솔용).
 * - 어휘는 StatusView 와 같다: 권한(401 · 403) → `denied`, 없음(404) → `not-found`, 그 밖 → `error`(다시 시도).
 *   503 은 `waiting`(서버가 아직 주지 않는 기능)이 아니다 — 잠시 응답하지 않는 것이라 다시 시도가 맞다.
 * - 서버 상세가 한국어면 그대로 보인다(서버가 사용자에게 쓴 말). 영어 상세는 보이지 않는다.
 */
export type ReadErrorKind = 'error' | 'denied' | 'not-found';

export interface PlainReadError {
  readonly kind: ReadErrorKind;
  /** 사용자에게 보일 한 문장(마침표로 끝난다). */
  readonly text: string;
  /** HTTP 상태 코드(있을 때). 화면 본문에 붙이지 않는다. */
  readonly code: string | null;
}

const AGAIN = '잠시 뒤 다시 해 보세요.';
const NETWORK = /failed to fetch|networkerror|load failed|fetch failed|network request failed/i;
const HANGUL = /[가-힣]/;

export function plainReadError(raw: string | null | undefined): PlainReadError {
  const message = (raw ?? '').trim();
  const status = /^(\d{3})\b/.exec(message)?.[1] ?? null;
  if (status !== null) {
    const n = Number(status);
    if (n === 401) return { kind: 'denied', text: '로그인이 필요합니다. 다시 로그인해 주세요.', code: status };
    if (n === 403) return { kind: 'denied', text: '이 내용을 볼 권한이 없습니다.', code: status };
    if (n === 404) return { kind: 'not-found', text: '찾는 내용이 없습니다.', code: status };
    if (n === 502 || n === 503) return { kind: 'error', text: `서버가 잠시 응답하지 않습니다. ${AGAIN}`, code: status };
    if (n === 408 || n === 504) return { kind: 'error', text: `서버 응답이 늦습니다. ${AGAIN}`, code: status };
    if (n >= 500) return { kind: 'error', text: `서버에서 문제가 생겼습니다. ${AGAIN}`, code: status };
    return { kind: 'error', text: '요청을 처리하지 못했습니다.', code: status };
  }
  if (NETWORK.test(message)) return { kind: 'error', text: '서버에 닿지 않습니다. 인터넷 연결을 확인하고 다시 해 보세요.', code: null };
  if (HANGUL.test(message)) return { kind: 'error', text: /[.!?]$/.test(message) ? message : `${message}.`, code: null };
  return { kind: 'error', text: AGAIN, code: null };
}
