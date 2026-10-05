const HTML_TAG = /<[^>]*>/g;
const HTML_NON_BREAKING_SPACE = /&(?:nbsp|#0*160|#x0*a0);/gi;

export function isArticleBodyBlank(html: string): boolean {
    return html
        .replace(HTML_TAG, '')
        .replace(HTML_NON_BREAKING_SPACE, ' ')
        .trim().length === 0;
}
