// 초성 (Hangul initial-consonant) search — a lightweight port of legacy util/convertSearch초성.ts.
// The legacy helper returns [raw, romanized, decomposed, automata-L1, automata-L2]; for the F2 modal
// dropdowns we only need the two paths that matter for a typed query: the raw name (substring match)
// and the leading-consonant string (so typing "ㅇㄷ" matches "업도"). matchesQuery() ORs the two.

const CHOSUNG = [
    'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
    'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
];

const HANGUL_BASE = 0xac00;
const HANGUL_LAST = 0xd7a3;

/** Extract the leading-consonant (초성) string of a Korean name. Non-Hangul chars pass through. */
export function toChosung(text: string): string {
    let out = '';
    for (const ch of text) {
        const code = ch.charCodeAt(0);
        if (code >= HANGUL_BASE && code <= HANGUL_LAST) {
            out += CHOSUNG[Math.floor((code - HANGUL_BASE) / 588)];
        } else {
            out += ch;
        }
    }
    return out;
}

/** True when `query` matches `name` by raw substring OR by leading-consonant substring (case-insensitive). */
export function matchesQuery(name: string, query: string): boolean {
    const q = query.trim();
    if (q === '') return true;
    const lowerName = name.toLowerCase();
    const lowerQ = q.toLowerCase();
    if (lowerName.includes(lowerQ)) return true;
    // 초성 match: only meaningful when the query itself is bare 초성 (or a substring of the name's 초성).
    return toChosung(name).includes(q);
}
