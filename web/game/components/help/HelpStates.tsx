'use client';

// 도움말 오류 · 대기 상태 — 오류 종류를 버리지 않고 가른다(설계서 §3.7). 모양은 공용 StatusView(P-X01).
import { StatusView } from '@opensamguk/ui';
import type { HelpErrorKind } from '@/lib/help';

export function LoadFailure({ kind, retry }: { kind: HelpErrorKind; retry: () => void }) {
    switch (kind) {
        case 'NOT_FOUND':
            return <StatusView kind="empty" title="이 도움말을 찾을 수 없습니다" body="주소가 바뀌었거나 없어진 도움말입니다." />;
        case 'WORLD_UNAVAILABLE':
            return <StatusView kind="waiting" title="서버가 준비 중이라 도움말도 잠시 쉽니다" body="점검이 끝나면 이 자리에서 바로 다시 볼 수 있습니다." />;
        case 'PROFILE_UNAVAILABLE':
            return <StatusView kind="empty" title="이 서버는 도움말을 제공하지 않습니다" body="이 서버의 규칙에는 도움말이 없습니다." />;
        case 'AUTH':
            return <StatusView kind="denied" title="로그인하면 볼 수 있습니다" howTo="다시 로그인해 주세요." />;
        case 'BAD_QUERY':
            return <StatusView kind="empty" title="찾는 말을 다시 적어 주세요" body="두 글자 이상, 여든 글자 이하로 적어 주세요." />;
        default:
            return <StatusView kind="error" title="도움말을 불러오지 못했습니다" onRetry={retry} />;
    }
}
