'use client';

import { Panel, plainReadError } from '@opensamguk/ui';
import type { GameSession } from '@/lib/campaign-session';

/** 장수가 있어야 뜻이 있는 화면을 열 수 없는 사유. null 이면 열 수 있다. */
export function campaignBlockReason(session: GameSession): string | null {
    if (session.loading) return '장수 정보를 불러오는 중입니다.';
    if (session.error) return `장수 정보를 불러오지 못했습니다 — ${plainReadError(session.error).text}`;
    if (session.generalId == null) return '이 서버에 장수가 없습니다. 장수를 만든 뒤에 열 수 있습니다.';
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

/**
 * 조회 상태를 한 줄로 — 불러오는 중 · 오류 · 규칙이 맞지 않는 서버. 데이터가 있으면 null.
 * 오류는 쉬운 말 한 문장이다(`plainReadError`) — 「503: Service Unavailable」 같은 원문은 보이지 않는다.
 */
export function campaignReadNotice(read: { loading: boolean; error: string | null }, status?: string | null): string | null {
    if (read.loading) return '불러오는 중입니다.';
    if (read.error) return `불러오지 못했습니다 — ${plainReadError(read.error).text}`;
    if (status === 'WRONG_RULE_PROFILE') return '이 서버는 지금 게임 규칙과 맞지 않습니다.';
    if (status === 'UNAVAILABLE') return '저장된 값을 읽을 수 없습니다.';
    // 옛 형식 월드 — 빈 목록(「없습니다」)으로 보이면 안 된다(K4 감사: 창고 · 수하가 비어 보였다).
    if (status === 'UNSUPPORTED_WORLD_FORMAT') return '이 서버는 지금 게임 규칙과 맞지 않습니다.';
    // 인물 일람 · 세력 요약 · 현 목록(`directory-reads.ts`)의 상태 — 빈 칸으로 두지 않는다.
    if (status === 'NO_GENERAL') return '이 서버에 장수가 없습니다.';
    if (status === 'NO_NATION') return '소속이 없어 세력 정보가 없습니다. 출사하거나 거병하면 보입니다.';
    return null;
}

/** 받은 값은 보이되 일부를 못 읽었을 때 한 줄(`PARTIAL`). 받은 칸은 그대로 두고 이 줄을 곁에 보인다. */
export function campaignPartialNotice(status?: string | null): string | null {
    return status === 'PARTIAL' ? '일부 값을 읽지 못했습니다 — 읽은 것만 보입니다.' : null;
}
