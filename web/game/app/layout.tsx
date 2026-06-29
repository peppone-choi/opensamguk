import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: '오픈삼국',
  description: 'Kotlin/Spring + Next.js 메모리 중심 CQRS 재작성',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
