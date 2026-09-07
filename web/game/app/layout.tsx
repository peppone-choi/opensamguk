import type { Metadata, Viewport } from 'next';
import { JetBrains_Mono, Noto_Serif_KR } from 'next/font/google';
// Pretendard 는 npm 패키지의 dynamic-subset CSS 를 번들에 넣는다(런타임 외부 요청 없음, OFL).
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';
import './globals.css';
import UiProviders from '@/components/UiProviders';

// 제목·연호·인물명(serif)과 수치·시각(mono)은 next/font 가 빌드 시 self-host 한다(ADR-LITE-049 · S1 타이포).
// preload 는 끈다 — 한글 slice 가 많아 unicode-range 로 필요한 조각만 내려받게 둔다.
const serif = Noto_Serif_KR({ weight: ['700', '900'], preload: false, display: 'swap', variable: '--font-serif-next' });
const mono = JetBrains_Mono({ weight: ['500', '700'], preload: false, display: 'swap', variable: '--font-mono-next' });

export const metadata: Metadata = {
  title: '오픈삼국',
  description: 'Kotlin/Spring + Next.js 메모리 중심 CQRS 재작성',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={`${serif.variable} ${mono.variable}`}>
      <body><UiProviders>{children}</UiProviders></body>
    </html>
  );
}
