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
