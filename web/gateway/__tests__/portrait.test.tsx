import { fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import { describe, expect, it, vi } from 'vitest';

import { DEFAULT_PORTRAIT, PORTRAIT_CDN, onPortraitError, portraitUrl } from '@/lib/portrait';

describe('gateway portrait helper', () => {
    it.each([null, '', '   '])('uses the default for missing picture value %j', (picture) => {
        expect(portraitUrl(picture, 0)).toBe(DEFAULT_PORTRAIT);
    });

    it.each(['a1b2c3d4.png', 'deadbeef.webp', '00000000.avif', 'ffffffff.jpg', '12345678.gif'])(
        'resolves imgsvr=1 canonical managed name %s to same-origin /d_pic/',
        (name) => {
            expect(portraitUrl(name, 1)).toBe(`/d_pic/${name}`);
        },
    );

    it('trims surrounding whitespace before resolving an imgsvr=1 managed name', () => {
        expect(portraitUrl('  a1b2c3d4.png  ', 1)).toBe('/d_pic/a1b2c3d4.png');
    });

    it.each([
        'uploaded.png', // 8자리 hex 아님
        'A1B2C3D4.png', // 대문자 hex 거부
        'a1b2c3d4.svg', // 관리 확장자 아님
        'a1b2c3d4', // 확장자 없음
        '../a1b2c3d4.png', // traversal 시도
        'a1b2c3d4.png/x', // 경로 주입 시도
        'deadbee.png', // 7자리
        'deadbeeff.png', // 9자리
    ])('falls back to the default for a non-canonical imgsvr=1 name %j', (picture) => {
        expect(portraitUrl(picture, 1)).toBe(DEFAULT_PORTRAIT);
    });

    it.each([1, 0, null, undefined])(
        'keeps the shared-CDN resolution for a bare code regardless of imgsvr=%j when not managed',
        (imgsvr) => {
            // imgsvr!=1(0/null/undefined)은 공유 CDN, imgsvr=1은 non-managed라 default — 회귀 방지.
            const url = portraitUrl('1001', imgsvr);
            expect(url).toBe(imgsvr ? DEFAULT_PORTRAIT : `${PORTRAIT_CDN}/1001.jpg`);
        },
    );

    it('replaces an unrelated nested default.jpg exactly once', () => {
        render(
            <img
                src="https://example.test/nested/default.jpg"
                onError={onPortraitError}
                alt="portrait"
            />,
        );
        const portrait = screen.getByRole('img', { name: 'portrait' }) as HTMLImageElement;
        const srcSetter = vi.spyOn(HTMLImageElement.prototype, 'src', 'set');
        try {
            fireEvent.error(portrait);
            expect(portrait.src).toBe(DEFAULT_PORTRAIT);
            expect(srcSetter).toHaveBeenCalledTimes(1);

            fireEvent.error(portrait);
            expect(srcSetter).toHaveBeenCalledTimes(1);
        } finally {
            srcSetter.mockRestore();
        }
    });

    it('falls back again when the same image element receives a new source', () => {
        render(<img src="https://example.test/a.png" onError={onPortraitError} alt="changing portrait" />);
        const portrait = screen.getByRole('img', { name: 'changing portrait' }) as HTMLImageElement;
        const srcSetter = vi.spyOn(HTMLImageElement.prototype, 'src', 'set');
        try {
            fireEvent.error(portrait);
            portrait.src = 'https://example.test/b.png';
            fireEvent.error(portrait);
            fireEvent.error(portrait);

            const fallbackAssignments = srcSetter.mock.calls.filter(([value]) => value === DEFAULT_PORTRAIT);
            expect(fallbackAssignments).toHaveLength(2);
        } finally {
            srcSetter.mockRestore();
        }
    });

    it('stops a canonical fallback self-error resolved from a relative CDN source', () => {
        render(
            <>
                <base href={`${PORTRAIT_CDN}/`} />
                <img src="default.jpg" onError={onPortraitError} alt="default portrait" />
            </>,
        );
        const portrait = screen.getByRole('img', { name: 'default portrait' }) as HTMLImageElement;
        const srcSetter = vi.spyOn(HTMLImageElement.prototype, 'src', 'set');
        try {
            fireEvent.error(portrait);
            fireEvent.error(portrait);

            expect(portrait.src).toBe(DEFAULT_PORTRAIT);
            expect(srcSetter).not.toHaveBeenCalled();
        } finally {
            srcSetter.mockRestore();
        }
    });
});
