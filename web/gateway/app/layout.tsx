import type { Metadata } from 'next';
// web/game 과 같은 폰트 계약(ADR-LITE-049 · S1 타이포) — 3종 모두 npm 패키지 CSS 로 self-host 한다.
// `next/font/google` 은 빌드 시 fonts.gstatic.com 의존 때문에 폐기했다(ADR-LITE-064).
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';
import '@fontsource-variable/noto-serif-kr';
import '@fontsource-variable/jetbrains-mono/wght.css';
import './globals.css';
import UiProviders from '@/components/UiProviders';

export const metadata: Metadata = {
    title: '오픈삼국',
    description: '게이트웨이 — 로그인 / 로비',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
    return (
        <html lang="ko">
            <body><UiProviders>{children}</UiProviders></body>
        </html>
    );
}
