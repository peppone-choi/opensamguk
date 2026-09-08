import { describe, expect, it } from 'vitest';
import { createPortraitResolver } from '../../shared/src/portraitResolver';
const resolver = createPortraitResolver('https://cdn.example');
describe('uploaded portrait bundles', () => {
    it.each([['original', 'hero'], ['portrait', 'card'], ['icon', 'icon']] as const)('routes %s to its own image', (variant, path) => {
        expect(resolver.portraitVariantUrl('aabbccdd.portrait', 1, variant)).toBe(`/profile-icons/aabbccdd.portrait/${path}.jpg`);
    });
    it('keeps old uploads and rejects arbitrary bundle paths', () => {
        expect(resolver.portraitVariantUrl('aabbccdd.png', 1, 'icon')).toBe('/d_pic/aabbccdd.png');
        expect(resolver.portraitUrl('../aabbccdd.portrait', 1)).toBe('/portrait-default.svg');
        expect(resolver.portraitUrl('AABBCCDD.portrait', 1)).toBe('/portrait-default.svg');
    });
});

// 1678 is uniquely 희지재 in 20 extracted scenarios; image registry10380 is 戯志才.
it('resolves the missing legacy 희지재 icon to the verified existing portrait in all variants', () => {
    for (const variant of ['original', 'portrait', 'icon'] as const) {
        const extension = variant === 'original' ? 'jpg' : 'png';
        expect(resolver.portraitVariantUrl('1678', 0, variant)).toBe(`https://cdn.example/portraits/rtk14/serving/${variant}/10380.${extension}`);
    }
    expect(resolver.portraitVariantUrl('1678', 1, 'icon')).toBe('/portrait-default.svg');
});
