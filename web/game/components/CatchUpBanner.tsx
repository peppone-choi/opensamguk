'use client';

export interface CatchUpInfo {
    active: boolean;
    multiplier: 2 | 4;
    backlogSeconds: number;
    remainingSeconds: number;
    etaAt: string | null;
}

function formatEta(etaAt: string | null): string {
    if (!etaAt) return '계산 중';
    const at = new Date(etaAt);
    if (Number.isNaN(at.getTime())) return '계산 중';
    return `${new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul', year: 'numeric', month: 'numeric', day: 'numeric',
        hour: '2-digit', minute: '2-digit', hour12: false,
    }).format(at)} (한국 시간)`;
}

/** Visible in the shared shell on desktop and mobile while turn recovery runs. */
export default function CatchUpBanner({ catchUp }: { catchUp: CatchUpInfo | null | undefined }) {
    if (!catchUp?.active) return null;
    return (
        <aside
            role="status"
            aria-label="밀린 턴 따라잡기"
            aria-live="polite"
            style={{
                padding: '8px 14px', background: 'var(--bg-elevated)', color: 'var(--text-primary)',
                borderBottom: '1px solid var(--border-subtle)', fontSize: 'var(--text-sm)',
                lineHeight: 1.5, overflowWrap: 'anywhere',
            }}
        >
            서버가 밀린 턴을 {catchUp.multiplier}배속으로 따라잡고 있습니다. 정상 속도 예상: {formatEta(catchUp.etaAt)}
        </aside>
    );
}
