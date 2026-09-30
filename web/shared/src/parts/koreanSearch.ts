/** 한글 이름 찾기 — 글자 그대로 또는 초성(예: 「ㅅㅇ」 → 순욱). 공백은 무시한다. */

const CHOSEONG = ['ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];
const CHOSEONG_SET = new Set(CHOSEONG);
const SYLLABLE_FIRST = 0xac00;
const SYLLABLE_LAST = 0xd7a3;
const PER_CHOSEONG = 588;

/** 한글 음절이면 초성, 아니면 그 글자 그대로. */
export function choseongOf(ch: string): string {
  const code = ch.charCodeAt(0);
  if (code < SYLLABLE_FIRST || code > SYLLABLE_LAST) return ch;
  return CHOSEONG[Math.floor((code - SYLLABLE_FIRST) / PER_CHOSEONG)];
}

function normalise(value: string): string {
  return value.replace(/\s+/g, '').toLowerCase();
}

/** `query` 의 각 글자가 초성이면 이름 글자의 초성과, 아니면 글자 그대로와 맞춘다. 이름 어디서든 이어서 맞으면 참. */
export function matchesKoreanName(name: string, query: string): boolean {
  const q = normalise(query);
  if (!q) return true;
  const n = normalise(name);
  for (let start = 0; start + q.length <= n.length; start += 1) {
    let ok = true;
    for (let i = 0; i < q.length; i += 1) {
      const qc = q[i];
      const nc = n[start + i];
      if (CHOSEONG_SET.has(qc) ? choseongOf(nc) !== qc : qc !== nc) {
        ok = false;
        break;
      }
    }
    if (ok) return true;
  }
  return false;
}
