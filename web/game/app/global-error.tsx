'use client';

import './globals.css';
import { StatusView } from '@opensamguk/ui';

/** 뿌리 레이아웃까지 깨졌을 때 — html · body 를 직접 그린다(Next global-error). */
export default function GlobalError({ error, reset }: { readonly error: Error & { readonly digest?: string }; readonly reset: () => void }) {
    return (
        <html lang="ko">
            <body>
                <main>
                    <StatusView kind="error" scope="page" title="화면을 불러오지 못했습니다" errorCode={error.digest} onRetry={reset} />
                </main>
            </body>
        </html>
    );
}
