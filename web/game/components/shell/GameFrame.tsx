'use client';

// 게임 안 셸 하나(v3.1 — ADR-LITE-049 2026-09-30 「v3.1 전체 승인」, 보드 V31SystemShell · Nav · MPage · Banner).
// 데스크톱 · 태블릿: 머리줄 48 + 알림 띠 + [레일 56 | 본문]. 모바일(< 768): 머리줄 56 + 알림 띠 + 본문 + 하단 탭 64.
// 레일과 하단 탭은 둘 다 그리고 CSS 미디어 쿼리로 하나만 보인다(배치 차이는 CSS 먼저).
// 옛 두 셸(Shell · GameShell 머리줄)을 대신한다. 월드 규칙 분기는 없다 — 제품 규칙은 휘하 하나다(ADR-LITE-065).

import { usePathname, useSearchParams } from 'next/navigation';
import { useCallback, useState, type ReactNode } from 'react';
import { Brand, Chip } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useSSE } from '@/hooks/useSSE';
import { usePresencePulse } from '@/hooks/usePresencePulse';
import { useTurnLoop } from '@/hooks/useTurnLoop';
import { useAuthOptional } from '@/lib/auth-context';
import { useRenown } from '@/lib/campaign-reads';
import { GameSessionProvider, useGameSession } from '@/lib/campaign-session';
import { LOBBY_HREF } from '@/lib/gatewayLinks';
import { MOBILE_TAB_KEYS, NAV31, groupHref, locateScreen, screenHref, type NavGroup } from '@/lib/nav31';
import { normalizeGamePathname } from '@/lib/serverGameUrl';
import { deliverTurnCompleted } from '@/lib/turnEvents';
import HelpDrawer from './HelpDrawer';
import NoticeBand from './NoticeBand';
import { ShellIcon, type ShellIconName } from './ShellIcon';
import styles from './shell.module.css';

/** 입장 흐름 — 레일 · 하단 탭 없이 머리줄만(보드 EntryHeader). */
const ENTRY_PATHS: ReadonlySet<string> = new Set(['join', 'register']);

/** 달 → 계절(v31system SEASONS: 봄 3–5 · 여름 6–8 · 가을 9–11 · 겨울 12–2). */
export function seasonOf(month: number): string {
  if (month >= 3 && month <= 5) return '봄';
  if (month >= 6 && month <= 8) return '여름';
  if (month >= 9 && month <= 11) return '가을';
  return '겨울';
}

export default function GameFrame({ children }: { readonly children: ReactNode }) {
  return (
    <GameSessionProvider>
      <Frame>{children}</Frame>
    </GameSessionProvider>
  );
}

function Frame({ children }: { readonly children: ReactNode }) {
  const pathname = usePathname() ?? '';
  const search = useSearchParams();
  const session = useGameSession();
  const auth = useAuthOptional();
  const { frontInfo, serverId } = session;
  const rest = normalizeGamePathname(pathname, serverId).replace(/^\/game\/?/, '');
  const located = locateScreen(rest, search?.toString() ?? '');
  const entry = ENTRY_PATHS.has(rest.split('/')[0] ?? '');
  const [menuOpen, setMenuOpen] = useState(false);

  // 턴 SSE 는 앱 전역에 하나 — 신호를 화면 구독자(useTurnRefresh)에게 나눠 준다(OPENSAM-196).
  const onTurn = useCallback(() => deliverTurnCompleted(), []);
  useSSE(onTurn);
  const hasGeneral = Boolean(frontInfo?.general.hasGeneral);
  usePresencePulse(hasGeneral, `${serverId}:${frontInfo?.global.year ?? ''}:${frontInfo?.global.month ?? ''}:${frontInfo?.global.turnPhase ?? ''}`);
  const { view, recheck } = useTurnLoop(serverId);
  const renown = useRenown();

  const month = frontInfo?.global.month;
  const season = month ? `${seasonOf(month)} · ${session.gameDate}` : null;
  const clock = view?.clock === '미정' ? '미정' : '확인 중'; // 개인 턴 시각은 서버 값(K3-02)이 올 때까지 짐작하지 않는다
  const generalName = frontInfo?.general.name ?? null;
  const allegiance = frontInfo?.nation?.name ? `${frontInfo.nation.name} 소속` : '재야';
  const isAdmin = auth?.user?.role === 'ADMIN';
  const helpView = search?.get('help') ?? null;
  const helpHref = withQuery(search, 'help', 'home');

  return (
    <div className={styles.frame} data-entry={entry || undefined}>
      <header className={styles.top}>
        <CampaignLink slug="" className={styles.logo} aria-label="작전실로">
          <Brand size="small" />
        </CampaignLink>
        <h1 className={styles.title}>{entry ? '입장' : located?.group.label ?? '게임'}</h1>
        <span className={styles.chips}>
          {season ? <span className={`os-chip ${styles.chip}`}>{season}</span> : null}
          {!entry ? <span className={`os-chip ${styles.chip} ${styles.wide}`}>다음 개인 턴 {clock}</span> : null}
          {!entry ? (
            <CampaignLink slug="mailbox" className={styles.iconButton} aria-label="서신">
              <ShellIcon name="mail" />
            </CampaignLink>
          ) : null}
          <a className={styles.iconButton} href={helpHref} aria-label="이 화면 도움말">
            <ShellIcon name="help" />
          </a>
          {entry ? <a className={`os-button os-button--ghost ${styles.lobby}`} href={LOBBY_HREF}>로비로</a> : null}
          {!entry && generalName ? (
            <CampaignLink slug="retinue" className={`${styles.who} ${styles.wide}`}>{`${generalName} · ${allegiance}`}</CampaignLink>
          ) : null}
          {!entry && hasGeneral ? <Chip tone="bronze" className={styles.wide}>{`명망 ${renown ?? '—'}`}</Chip> : null}
        </span>
      </header>
      {!entry ? <NoticeBand band={view?.band ?? null} onRecheck={recheck} /> : null}
      <div className={styles.body}>
        {!entry ? (
          <nav className={styles.rail} aria-label="게임 메뉴">
            {NAV31.map((group) => (
              <GroupLink key={group.key} group={group} current={located?.group.key === group.key} className={styles.railItem} />
            ))}
            <span className={styles.railGap} aria-hidden="true" />
            <a className={styles.railItem} href={helpHref}>
              <ShellIcon name="help" />
              <span>도움말</span>
            </a>
            {isAdmin ? (
              <CampaignLink slug="admin" className={styles.railItem}>
                <ShellIcon name="admin" />
                <span>관리</span>
              </CampaignLink>
            ) : null}
          </nav>
        ) : null}
        <main className={styles.main} aria-label="게임 콘텐츠">{children}</main>
        {helpView ? <HelpDrawer view={helpView} closeHref={withQuery(search, 'help', null)} /> : null}
      </div>
      {!entry ? (
        <nav className={styles.tabbar} aria-label="게임 메뉴">
          {MOBILE_TAB_KEYS.map((key) => {
            const group = NAV31.find((g) => g.key === key)!;
            return <GroupLink key={key} group={group} current={located?.group.key === key} className={styles.tab} />;
          })}
          <button type="button" className={styles.tab} aria-haspopup="dialog" aria-expanded={menuOpen} onClick={() => setMenuOpen(true)}>
            <ShellIcon name="menu" />
            <span>전체</span>
          </button>
        </nav>
      ) : null}
      {menuOpen ? <MenuSheet current={located?.group.key ?? null} isAdmin={isAdmin} onClose={() => setMenuOpen(false)} /> : null}
    </div>
  );
}

function GroupLink({ group, current, className }: { readonly group: NavGroup; readonly current: boolean; readonly className: string }) {
  const href = groupHref(group);
  const body = (
    <>
      <ShellIcon name={group.key as ShellIconName} />
      <span>{group.label}</span>
    </>
  );
  if (href === null) return <span className={className} aria-disabled="true">{body}</span>;
  return (
    <CampaignLink slug={href} className={className} aria-current={current ? 'page' : undefined}>
      {body}
    </CampaignLink>
  );
}

/** 모바일 「전체」 — 모든 묶음과 그 화면(보드 V3MMenu). 모달이 아니라 하단 시트다. */
function MenuSheet({ current, isAdmin, onClose }: { readonly current: string | null; readonly isAdmin: boolean; readonly onClose: () => void }) {
  return (
    <div className={styles.sheetLayer}>
      <button type="button" className={styles.scrim} aria-label="메뉴 닫기" onClick={onClose} />
      <section className={styles.sheet} role="dialog" aria-label="전체 메뉴" onKeyDown={(event) => { if (event.key === 'Escape') onClose(); }}>
        <div className={styles.sheetHead}>
          <span className={styles.sheetTitle}>전체</span>
          <button type="button" className={styles.iconButton} aria-label="메뉴 닫기" onClick={onClose} autoFocus>
            <ShellIcon name="close" />
          </button>
        </div>
        <div className={styles.sheetBody}>
          {NAV31.map((group) => (
            <div key={group.key} className={styles.sheetGroup}>
              <span className={styles.sheetGroupHead} aria-current={current === group.key ? 'true' : undefined}>{group.label}</span>
              {group.screens.map((screen) => {
                const href = screenHref(screen);
                return href === null ? (
                  <span key={screen.label} className={styles.sheetItem} aria-disabled="true">
                    {screen.label}<span className={styles.soon}>준비 중</span>
                  </span>
                ) : (
                  <CampaignLink key={screen.label} slug={href} className={styles.sheetItem} onClick={onClose}>{screen.label}</CampaignLink>
                );
              })}
            </div>
          ))}
          <a className={styles.sheetItem} href={LOBBY_HREF}>로비로</a>
          {isAdmin ? <CampaignLink slug="admin" className={styles.sheetItem} onClick={onClose}>관리</CampaignLink> : null}
        </div>
      </section>
    </div>
  );
}

/** 지금 쿼리에 한 값을 넣거나(값) 빼서(null) 만든 `?…` 주소. 경로는 그대로다. */
function withQuery(search: URLSearchParams | null, key: string, value: string | null): string {
  const next = new URLSearchParams(search?.toString() ?? '');
  if (value === null) next.delete(key);
  else next.set(key, value);
  const text = next.toString();
  return text ? `?${text}` : '?';
}
