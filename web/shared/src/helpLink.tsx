'use client';

import { createContext, useContext, type MouseEvent, type ReactNode } from 'react';

/** 도움말 주제 id → 그 도움말을 여는 주소. 링크의 href(가운데 누르기 · 새 탭 · 링크 복사)에 쓴다. */
export type HelpHref = (topicId: string) => string;

/** 부품의 「도움말 — …」 링크가 쓰는 것 — 주소와, 같은 문서 안에서 여는 법(없으면 링크로 간다). */
export interface HelpLink {
  readonly href: HelpHref;
  /** 보통 누르기를 이것으로 연다(예: router.push(href, { scroll: false })). 가운데 누르기 · 새 탭은 href 로 간다. */
  readonly open?: (topicId: string) => void;
}

/** 맥락이 없을 때 — 지금 경로에 `?help=<id>` 만 붙인다(다른 쿼리는 지운다). 게임 화면은 /game 레이아웃이 HelpLinkProvider 로 바꾼다. */
export const defaultHelpHref: HelpHref = (topicId) => `?help=${encodeURIComponent(topicId)}`;

const HelpLinkContext = createContext<HelpLink>({ href: defaultHelpHref });

/**
 * 부품(ReasonTooltip · StatusView denied)의 「도움말 — …」 링크를 정한다. 지금 쿼리(`?tab=orders` 등)를 지키는 주소와 같은 문서
 * 안에서 여는 법을 화면마다 넘기지 않아도 되게, /game 레이아웃이 한 번 감싼다(값은 주소가 바뀔 때마다 다시 만든다).
 */
export function HelpLinkProvider({ value, children }: { readonly value: HelpLink; readonly children: ReactNode }) {
  return <HelpLinkContext.Provider value={value}>{children}</HelpLinkContext.Provider>;
}

/** 부품이 쓸 도움말 링크 — 부품에 직접 넘긴 주소가 있으면 그것을 쓰고 링크로 간다(맥락의 open 은 쓰지 않는다). */
export function useHelpLink(overrideHref?: HelpHref): HelpLink {
  const fromContext = useContext(HelpLinkContext);
  return overrideHref ? { href: overrideHref } : fromContext;
}

/** 보통 누르기인지 — 가운데 · 수정 키 누르기는 브라우저에 맡긴다(새 탭 · 새 창). */
export function isPlainClick(event: MouseEvent): boolean {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
}
