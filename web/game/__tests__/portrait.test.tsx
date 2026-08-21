import { fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import { describe, expect, it } from 'vitest';

import { DEFAULT_PORTRAIT, PORTRAIT_CDN, onPortraitError, portraitUrl } from '@/lib/portrait';

describe('game portrait helper', () => {
    it.each([null, undefined, ''])('uses the default for missing picture value %j', (picture) => {
        expect(portraitUrl(picture, 0)).toBe(DEFAULT_PORTRAIT);
    });

    it('adds jpg to a bare shared portrait code (imgsvr=0)', () => {
        expect(portraitUrl('1001', 0)).toBe(`${PORTRAIT_CDN}/1001.jpg`);
    });

    it('preserves a supported canonical extension (imgsvr=0)', () => {
        expect(portraitUrl('portrait.webp', 0)).toBe(`${PORTRAIT_CDN}/portrait.webp`);
    });

    it.each([undefined, null, 0])('keeps the shared-CDN path when imgsvr is falsy (%j)', (imgsvr) => {
        expect(portraitUrl('1001', imgsvr)).toBe(`${PORTRAIT_CDN}/1001.jpg`);
    });

    it.each([
        '../../secret.jpg', // traversal 시도
        'a/b.jpg', // 경로 주입 시도
        '1001.svg', // 지원하지 않는 확장자
        'name with space.jpg', // 공백
        '1001?x=1', // 쿼리 주입 시도
        'https://evil.test/a.jpg', // 절대 URL 주입 시도
    ])('falls back to the default for a non-whitelisted imgsvr=0 name %j', (picture) => {
        expect(portraitUrl(picture, 0)).toBe(DEFAULT_PORTRAIT);
    });

    it.each(['a1b2c3d4.png', 'deadbeef.webp', '00000000.avif', 'ffffffff.jpg', '12345678.gif'])(
        'resolves imgsvr=1 canonical managed name %s to same-origin /d_pic/',
        (name) => {
            expect(portraitUrl(name, 1)).toBe(`/d_pic/${name}`);
        },
    );

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

    it('runs the guarded onError fallback exactly once', () => {
        render(<img src="https://example.test/a.png" onError={onPortraitError} alt="portrait" />);
        const portrait = screen.getByRole('img', { name: 'portrait' }) as HTMLImageElement;

        fireEvent.error(portrait);
        expect(portrait.src).toBe(DEFAULT_PORTRAIT);

        // 이미 기본 초상 — 두 번째 error는 src를 다시 바꾸지 않는다(무한 루프 방지).
        fireEvent.error(portrait);
        expect(portrait.src).toBe(DEFAULT_PORTRAIT);
    });
});
