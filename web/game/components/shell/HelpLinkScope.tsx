'use client';

import { usePathname, useSearchParams } from 'next/navigation';
import { useMemo, type ReactNode } from 'react';
import { HelpLinkProvider, type HelpLink } from '@opensamguk/ui';
import { useOpenHelp } from '@/hooks/useOpenHelp';
import { helpHref } from '@/lib/help-route';

/**
 * /game 화면 안 공용 부품(ReasonTooltip · StatusView denied)의 「도움말 — …」 링크(K0 2026-10-01).
 * 주소는 지금 경로 + 지금 쿼리에 `help` 만 바꿔 넣은 것이라 `?tab=orders` · `?county=7` 이 남는다(가운데 누르기 · 새 탭 · 링크 복사).
 * 보통 누르기는 router.push(scroll: false) — 문서를 다시 받지 않고 같은 화면 위에 도움말 서랍을 연다.
 * 주소 규칙은 서랍 · `useOpenHelp` 와 같은 `helpHref` 하나다. 주소가 바뀔 때마다(서랍 열고 닫기 · 탭 바꾸기) 다시 만든다.
 * 화면이 onHelp 를 넘기면 그쪽이 먼저다(K7 useOpenHelp · useReasonHelp).
 */
export default function HelpLinkScope({ children }: { readonly children: ReactNode }) {
  const pathname = usePathname() ?? '';
  const search = useSearchParams();
  const open = useOpenHelp();
  const value = useMemo<HelpLink>(
    () => ({ href: (topicId: string) => helpHref(pathname, search, topicId), open }),
    [pathname, search, open],
  );
  return <HelpLinkProvider value={value}>{children}</HelpLinkProvider>;
}
