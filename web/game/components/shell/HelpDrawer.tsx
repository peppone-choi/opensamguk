'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { Suspense, lazy, useCallback } from 'react';
import { StatusView, useViewportClass } from '@opensamguk/ui';
import { formatHelpView, parseHelpView, type HelpView } from '@/lib/help-route';
import { helpScreenOf } from '@/lib/help-screen-of';
import styles from './shell.module.css';

// 본문(원장 표 · 부품)은 서랍을 열 때 받는다 — 셸은 모든 게임 화면에 실리므로 닫힌 서랍 값을 모든 화면이 내지 않게.
const HelpPanel = lazy(() => import('@/components/help/HelpPanel').then((m) => ({ default: m.HelpPanel })));

/**
 * 도움말 서랍(보드 Drawers · V31K7Help — 데스크톱 400 · 태블릿 360 · 모바일 머리줄 아래 가득). 모달이 아니다 — 본문을 가리지 않고 옆에 선다.
 * `?help=<보기>`(lib/help-route 형식)가 있으면 열린다. 서랍 안의 이동은 같은 쿼리만 바꾸고(찾기어는 replace), 닫기는 그 쿼리를 뺀 주소다.
 * 「이 화면」은 셸이 찾은 지금 화면(묶음 · 화면 경로)에서 고른다. 첫걸음 진척은 연습 서버 판별(계약판 K7-03) 전까지 본 서버 안내판이다.
 */
export default function HelpDrawer({ view, closeHref, groupKey, screenPath }: {
  readonly view: string;
  readonly closeHref: string;
  readonly groupKey?: string | null;
  readonly screenPath?: string | null;
}) {
  const router = useRouter();
  const pathname = usePathname() ?? '';
  const search = useSearchParams();
  const viewport = useViewportClass();
  const parsed: HelpView = parseHelpView(view) ?? { kind: 'home' };

  const navigate = useCallback((next: HelpView, mode: 'push' | 'replace' = 'push') => {
    const query = new URLSearchParams(search?.toString() ?? '');
    query.set('help', formatHelpView(next));
    const url = `${pathname}?${query.toString()}`;
    if (mode === 'replace') router.replace(url, { scroll: false });
    else router.push(url, { scroll: false });
  }, [pathname, router, search]);
  const close = useCallback(() => {
    router.push(closeHref === '?' ? pathname : `${pathname}${closeHref}`, { scroll: false });
  }, [closeHref, pathname, router]);

  return (
    <aside className={styles.drawer} aria-label="도움말" data-help-view={view}>
      {/* 모바일은 검색칸에 바로 포커스하지 않는다 — 자판이 서랍을 덮는다. 폭을 재기 전(null)에도 하지 않는다. */}
      <Suspense fallback={<StatusView kind="loading" rows={6} />}>
        <HelpPanel view={parsed} onNavigate={navigate} onBack={parsed.kind === 'home' ? undefined : () => router.back()} onClose={close}
          screen={helpScreenOf(groupKey, screenPath)} practice={false} variant={viewport === 'mobile' ? 'sheet' : 'drawer'}
          autoFocus={viewport === 'tablet' || viewport === 'desktop'} />
      </Suspense>
    </aside>
  );
}
