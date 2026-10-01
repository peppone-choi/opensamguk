'use client';

// 게임 화면에서 도움말 서랍(셸 HelpDrawer, `?help=`)을 여는 길.
// 공용 부품(사유 시트 · InputAction · StatusView 「거부됨」)의 「도움말」 링크는 `onHelp` 가 없으면 `?help=<값>` 로 가서
// 다른 쿼리(`?tab=orders` · `?county=7`)를 지우고 문서를 다시 받는다. 게임 화면은 `onHelp` 에 이 함수를 넘긴다 —
// 사유 시트라면 `useReasonHelp(code, inputId)` 가 `onHelp` 까지 돌려주니 그 결과를 펼치면 된다.
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useCallback } from 'react';
import { helpHref, type HelpView } from '@/lib/help-route';

export function useOpenHelp(): (value: string | HelpView) => void {
    const router = useRouter();
    const pathname = usePathname() ?? '';
    const search = useSearchParams();
    return useCallback((value: string | HelpView) => {
        router.push(helpHref(pathname, search, value), { scroll: false });
    }, [pathname, router, search]);
}
