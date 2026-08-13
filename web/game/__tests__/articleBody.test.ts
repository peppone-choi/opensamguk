import { describe, expect, it, vi } from 'vitest';
import { isArticleBodyBlank } from '@/app/game/board/articleBody';

describe('isArticleBodyBlank', () => {
    it('keeps HTML whitespace bodies empty without requiring browser DOM APIs', () => {
        vi.stubGlobal('document', undefined);

        try {
            expect(isArticleBodyBlank('<p> </p>')).toBe(true);
            expect(isArticleBodyBlank('<p>&nbsp;</p>')).toBe(true);
            expect(isArticleBodyBlank('<p>&#160;</p>')).toBe(true);
            expect(isArticleBodyBlank('<p>&#xA0;</p>')).toBe(true);
        } finally {
            vi.unstubAllGlobals();
        }
    });
});
