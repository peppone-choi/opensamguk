// 서신 본문 글자 수 — 보이는 글자로 센다(서식 태그는 세지 않는다). K6 설계서 §3.8 「카운터는 보이는 글자 수로」.
// 한도 500은 지금 메일함과 같다. 서버엔 sendMessage 길이 한도가 아직 없다(HtmlSanitizer — 계약판 U-03에 요청).
export const MAIL_TEXT_MAX = 500;

const ENTITY: Readonly<Record<string, string>> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

export function visibleText(html: string): string {
    return html
        .replace(/<br\s*\/?>/gi, '\n')
        .replace(/<\/p>\s*<p[^>]*>/gi, '\n')
        .replace(/<[^>]*>/g, '')
        .replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e: string) => {
            if (e[0] === '#') {
                const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
                return Number.isFinite(code) ? String.fromCodePoint(code) : m;
            }
            return ENTITY[e.toLowerCase()] ?? m;
        });
}

export function visibleLength(html: string): number {
    return [...visibleText(html)].length;
}

export function isBlank(html: string): boolean {
    return visibleText(html).trim() === '';
}

/**
 * 서식 없는 글(서랍의 짧은 서신)을 서신 본문으로 — 꺾쇠 · 앰퍼샌드 · 따옴표를 글자로 바꾸고 줄바꿈은 `<br>`로.
 * 쓴 글이 태그로 읽히지 않게 한다(본문은 받는 쪽에서 SafeHtml로 그린다). 글자 수는 visibleLength로 센다(같은 500).
 */
export function plainToHtml(text: string): string {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
        .replace(/\r?\n/g, '<br>');
}
