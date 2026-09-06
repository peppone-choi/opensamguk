import type { Metadata } from 'next';
import { JetBrains_Mono, Noto_Serif_KR } from 'next/font/google';
// Pretendard 는 npm 패키지의 dynamic-subset CSS 를 번들에 넣는다(예전 jsDelivr <link> 런타임 요청 제거, OFL).
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';
import './globals.css';
import UiProviders from '@/components/UiProviders';

// web/game 과 같은 폰트 계약(ADR-LITE-049 · S1 타이포). next/font 가 빌드 시 self-host 한다.
const serif = Noto_Serif_KR({ weight: ['700', '900'], preload: false, display: 'swap', variable: '--font-serif-next' });
const mono = JetBrains_Mono({ weight: ['500', '700'], preload: false, display: 'swap', variable: '--font-mono-next' });

export const metadata: Metadata = {
    title: '오픈삼국',
    description: '게이트웨이 — 로그인 / 로비',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
    return (
        <html lang="ko" className={`${serif.variable} ${mono.variable}`}>
            <body><UiProviders>{children}</UiProviders></body>
        </html>
    );
}
