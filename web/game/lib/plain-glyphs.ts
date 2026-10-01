// 서버 글자 속 한자 행정 단위를 쉬운 말로 옮기는 한 곳 표(K0 2026-10-01 — 코드 안 한자 대조는 한 표로).
//
// 서버 라벨(예: 월단평 원인 「縣 점령」)에 한자가 섞여 오면 화면은 한글 읽기로 보인다(「현 점령」).
// 한자는 \u 이스케이프로 적는다 — 한자 lint(web_copy_lint hanja)는 화면 글자를 세는 것이라 대조표는 세지 않게 한다.
// 새 글자가 필요하면 여기에만 더한다. 지명 자체(許 → 허)는 여기서 옮기지 않는다 — 城 표 · 서버 한글 이름을 쓴다.

export const UNIT_GLYPH_READING: Readonly<Record<string, string>> = {
    // 縣
    '\u7E23': '현',
};

const PATTERN = new RegExp(`[${Object.keys(UNIT_GLYPH_READING).join('')}]`, 'g');

/** 서버 글자 속 행정 단위 한자를 한글 읽기로. 다른 글자는 그대로. */
export function plainGlyphs(text: string): string {
    return text.replace(PATTERN, (ch) => UNIT_GLYPH_READING[ch] ?? ch);
}
