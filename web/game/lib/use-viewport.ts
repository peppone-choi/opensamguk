'use client';

// 화면 폭 판정 — 공용 MEDIA(K3 `web/shared/src/breakpoints.ts`, v3.1 3단) 값만 쓴다. 수치를 따로 적지 않는다.
// 공용 훅이 @opensamguk/ui 에 생기면 그쪽으로 바꾼다(K4 화면 조립 전용 임시 자리).
// 첫 그림(SSR · 측정 전)은 null — 부른 쪽이 뼈대를 보이고, 데스크톱 · 모바일 모양을 짐작해 깜빡이지 않게 한다.

import { useEffect, useState } from 'react';
import { MEDIA } from '@opensamguk/ui';

export function useIsMobile(): boolean | null {
    const [mobile, setMobile] = useState<boolean | null>(null);
    useEffect(() => {
        if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
            setMobile(false);
            return;
        }
        const q = window.matchMedia(MEDIA.mobile);
        const on = () => setMobile(q.matches);
        on();
        q.addEventListener('change', on);
        return () => q.removeEventListener('change', on);
    }, []);
    return mobile;
}
