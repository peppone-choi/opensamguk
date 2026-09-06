'use client';

import { PortraitResolverProvider } from '@opensamguk/ui';
import type { ReactNode } from 'react';
import { portraitResolver } from '@/lib/portrait';

/** 앱 전역 UI 컨텍스트 — 초상 resolver(NEXT_PUBLIC_IMAGE_CDN 반영). 루트 레이아웃이 감싼다. */
export default function UiProviders({ children }: { readonly children: ReactNode }) {
    return <PortraitResolverProvider resolver={portraitResolver}>{children}</PortraitResolverProvider>;
}
