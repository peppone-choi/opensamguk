'use client';

// 게임 화면에서 도움말 서랍(셸 HelpDrawer, `?help=`)을 여는 길 — 지금 경로 · 다른 쿼리(`?tab=orders` · `?county=7`)는 두고
// `help` 만 넣어 문서를 다시 받지 않고 연다.
// 공용 부품(사유 시트 · InputAction · StatusView 「거부됨」)은 /game 레이아웃의 HelpLinkScope 가 같은 규칙으로 이미 연다 —
// 부품에 `onHelp` 를 꼭 넘길 필요는 없다. 이 훅은 부품 밖(직접 만든 단추 · 메뉴 · 키보드 단축키)에서 서랍을 열 때 쓴다.
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
