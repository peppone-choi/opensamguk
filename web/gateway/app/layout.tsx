import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
    title: '오픈삼국',
    description: '게이트웨이 — 로그인 / 로비',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
    return (
        <html lang="ko">
            <head>
                <link
                    rel="stylesheet"
                    href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css"
                />
            </head>
            <body>{children}</body>
        </html>
    );
}
