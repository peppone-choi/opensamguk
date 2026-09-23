import type { Metadata, Viewport } from 'next';
// 폰트 3종 모두 npm 패키지의 unicode-range 분할 CSS 를 번들에 넣는다 — 빌드·런타임 모두 외부 요청이 없다.
// `next/font/google` 은 빌드 시 fonts.gstatic.com 을 받아오다 간헐 실패로 CI·배포를 깨뜨려 폐기했다(ADR-LITE-064).
// 서브셋 분할이 유지되므로 브라우저는 실제로 쓰인 글자 범위의 조각만 내려받는다(한글 124조각).
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';
import '@fontsource-variable/noto-serif-kr';
import '@fontsource-variable/jetbrains-mono/wght.css';
import './globals.css';
import UiProviders from '@/components/UiProviders';

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
    <html lang="ko">
      <body><UiProviders>{children}</UiProviders></body>
    </html>
  );
}
