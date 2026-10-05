'use client';

import { StatusView } from '@opensamguk/ui';

/** 게이트웨이 화면이 그리다 깨졌을 때(Next 오류 경계) — 「다시 시도 + 오류 번호」(보드 P-X01 오류). */
export default function GatewayError({ error, reset }: { readonly error: Error & { readonly digest?: string }; readonly reset: () => void }) {
    return (
        <main>
            <StatusView kind="error" scope="page" title="화면을 불러오지 못했습니다" errorCode={error.digest} onRetry={reset} />
        </main>
    );
}
