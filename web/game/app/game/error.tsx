'use client';

import { CrashScreen } from '@/components/states/PageStates';

/** /game 화면이 그리다 깨졌을 때 — 셸은 남기고 본문만 「다시 시도 + 오류 번호」(보드 P-X01 오류). */
export default function GameError({ error, reset }: { readonly error: Error & { readonly digest?: string }; readonly reset: () => void }) {
    return <CrashScreen digest={error.digest} onRetry={reset} />;
}
