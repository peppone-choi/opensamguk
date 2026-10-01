'use client';

import { createContext, useContext, type ReactNode } from 'react';

/** 도움말 주제 id → 그 도움말을 여는 주소. 링크의 href(가운데 누르기 · 새 탭 · 링크 복사)에 쓴다. */
export type HelpHref = (topicId: string) => string;

/** 맥락이 없을 때 — 지금 경로에 `?help=<id>` 만 붙인다(다른 쿼리는 지운다). 게임 화면은 HelpLinkProvider 로 바꾼다. */
export const defaultHelpHref: HelpHref = (topicId) => `?help=${encodeURIComponent(topicId)}`;

const HelpLinkContext = createContext<HelpHref>(defaultHelpHref);

/**
 * 부품(ReasonTooltip · StatusView denied)의 「도움말 — …」 링크 주소를 정한다. 지금 쿼리(`?tab=orders` 등)를 지키는 주소를
 * 화면마다 넘기지 않아도 되게, /game 레이아웃이 한 번 감싼다. 값은 주소가 바뀔 때마다 다시 만든다(useSearchParams).
 */
export function HelpLinkProvider({ href, children }: { readonly href: HelpHref; readonly children: ReactNode }) {
  return <HelpLinkContext.Provider value={href}>{children}</HelpLinkContext.Provider>;
}

/** 부품이 쓸 도움말 주소 — 부품에 직접 넘긴 것이 맥락보다 앞선다. */
export function useHelpHref(override?: HelpHref): HelpHref {
  const fromContext = useContext(HelpLinkContext);
  return override ?? fromContext;
}
