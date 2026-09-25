'use client';

import { Panel } from '@opensamguk/ui';
import type { GameSession } from '@/lib/hwiha-session';

/** 휘하 화면을 열 수 없는 사유. null 이면 열 수 있다. */
export function hwihaBlockReason(session: GameSession): string | null {
    if (session.loading) return '장수 정보를 불러오는 중입니다.';
    if (session.error) return `장수 정보를 불러오지 못했습니다 — ${session.error}`;
    if (session.generalId == null) return '이 서버에 장수가 없습니다. 장수를 만든 뒤에 열 수 있습니다.';
    if (!session.isHwihaWorld) return '이 서버는 휘하 규칙이 아닙니다. 휘하 규칙 서버에서만 쓰는 화면입니다.';
    return null;
}

/** 셸 본문 자리에 사유를 보인다. */
export function Blocked({ reason }: { reason: string }) {
    return (
        <div style={{ padding: 24, display: 'flex', justifyContent: 'center' }}>
            <Panel style={{ padding: 20, maxWidth: 520, width: '100%' }}>
                <p style={{ margin: 0, color: 'var(--text-2)' }}>{reason}</p>
            </Panel>
        </div>
    );
}

/**
 * 패널 안의 빈 상태 한 줄. 목을 걷어낸 자리에 무엇이 없는지 말한다 — 없는 값을 지어내지 않는다.
 */
export function Empty({ children }: { children: React.ReactNode }) {
    return (
        <p
            style={{
                margin: '8px 0 0',
                padding: '12px',
                border: '1px dashed var(--line-2)',
                color: 'var(--muted)',
                fontSize: 13,
            }}
        >
            {children}
        </p>
    );
}

/** 조회 상태를 한 줄로 — 불러오는 중·오류·휘하 월드 아님. 데이터가 있으면 null. */
export function hwihaReadNotice(read: { loading: boolean; error: string | null }, status?: string | null): string | null {
    if (read.loading) return '불러오는 중입니다.';
    if (read.error) return `불러오지 못했습니다 — ${read.error}`;
    if (status === 'WRONG_RULE_PROFILE') return '휘하 규칙 서버가 아닙니다.';
    if (status === 'UNAVAILABLE') return '저장된 값을 읽을 수 없습니다.';
    return null;
}
