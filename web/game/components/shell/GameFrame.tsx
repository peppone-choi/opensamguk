'use client';

// 게임 안 셸 하나(v3.1 — ADR-LITE-049 2026-09-30 「v3.1 전체 승인」, 보드 V31SystemShell · Nav · MPage · Banner).
// 데스크톱 · 태블릿: 머리줄 48 + 알림 띠 + [레일 56 | 본문]. 모바일(< 768): 머리줄 56 + 알림 띠 + 본문 + 하단 탭 64.
// 레일과 하단 탭은 둘 다 그리고 CSS 미디어 쿼리로 하나만 보인다(배치 차이는 CSS 먼저).
// 옛 두 셸(Shell · GameShell 머리줄)을 대신한다. 월드 규칙 분기는 없다 — 제품 규칙은 휘하 하나다(ADR-LITE-065).

import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { Suspense, lazy, useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { Brand, Chip, Icon, StatusView, useViewportClass } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import SeasonPanel from '@/components/season/SeasonPanel';
import { useSSE } from '@/hooks/useSSE';
import { usePresencePulse } from '@/hooks/usePresencePulse';
import { useTurnLoop } from '@/hooks/useTurnLoop';
import { useAuthOptional } from '@/lib/auth-context';
import { useRenown } from '@/lib/campaign-reads';
import { GameSessionProvider, useGameSession } from '@/lib/campaign-session';
import { LOBBY_HREF } from '@/lib/gatewayLinks';
import { MOBILE_TAB_KEYS, NAV31, groupHref, locateScreen, screenHref, type NavGroup } from '@/lib/nav31';
import { hasSeasonNews, isGameMonth, seasonOf } from '@/lib/season';
import { normalizeGamePathname } from '@/lib/serverGameUrl';
import { deliverTurnCompleted } from '@/lib/turnEvents';
import HelpDrawer from './HelpDrawer';
import NoticeBand from './NoticeBand';
import { ShellIcon, type ShellIconName } from './ShellIcon';
import { SHELL_PAGE_CHIPS_ID } from './slots';
import styles from './shell.module.css';

// 머리줄 서신 서랍(P-Q02, K6) — 셸은 모든 게임 화면에 실리므로 서랍을 열 때 받는다.
const MailDrawer = lazy(() => import('@/components/mail/MailDrawer'));

/** 입장 흐름 — 레일 · 하단 탭 없이 머리줄만(보드 EntryHeader). */
const ENTRY_PATHS: ReadonlySet<string> = new Set(['join', 'register', 'create']);

/** 달 → 계절 — 정본은 lib/season.ts(서버 확정값 world-event-values.json 과 같은 경계). 셸 시험 · 부르는 곳을 위해 다시 내보낸다. */
export { seasonOf };

/** 계절 칩이 여는 자리(보드 V31SystemSeason · MSeason). 내용은 K8 SeasonPanel. */
const SEASON_DIALOG_ID = 'season-dialog';
const SEASON_TITLE_ID = 'season-dialog-title';

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
  const entry = ENTRY_PATHS.has(rest.split('/')[0] ?? '')
    || (rest === '' && frontInfo?.general.hasGeneral === false);
  // 작전실(보드 V31K4MWarRoom) — 모바일에서 머리줄이 줄 없이 지도 위 첫 줄 칩으로 뜬다. 판별은 경로, 폭은 CSS(< 768)가 한다.
  const warRoom = !entry && located?.group.key === 'war';
  // 머리줄이 여는 층은 한 번에 하나 — 모바일 「전체」 시트 · 계절 패널 · 도움말 서랍(?help=) · 서신 서랍(?mail=)이 함께 열리지 않는다.
  const [open, setOpen] = useState<'menu' | 'season' | null>(null);
  const viewport = useViewportClass();
  const seasonChip = useRef<HTMLButtonElement>(null);
  // 닫기 단추 · Esc · 모바일 덮개는 초점을 칩으로 돌린다. 데스크톱 바깥 누름은 돌리지 않는다 — 누른 입력칸 · 단추가 초점을 지킨다.
  const closeSeason = useCallback(() => {
    setOpen(null);
    window.setTimeout(() => seasonChip.current?.focus(), 0);
  }, []);
  const dismissSeason = useCallback(() => setOpen(null), []);

  // 턴 SSE 는 앱 전역에 하나 — 신호를 화면 구독자(useTurnRefresh)에게 나눠 준다(OPENSAM-196).
  const onTurn = useCallback(() => deliverTurnCompleted(), []);
  useSSE(onTurn);
  const hasGeneral = Boolean(frontInfo?.general.hasGeneral);
  usePresencePulse(hasGeneral, `${serverId}:${frontInfo?.global.year ?? ''}:${frontInfo?.global.month ?? ''}:${frontInfo?.global.turnPhase ?? ''}`);
  const { view, maintenance: closed, recheck } = useTurnLoop(serverId);
  const renown = useRenown();

  const month = frontInfo?.global.month;
  const season = isGameMonth(month) ? `${seasonOf(month)} · ${session.gameDate}` : null;
  const clock = view?.clock === '미정' ? '미정' : '확인 중'; // 개인 턴 시각은 서버 값(K3-02)이 올 때까지 짐작하지 않는다
  const generalName = frontInfo?.general.name ?? null;
  const allegiance = frontInfo?.nation?.name ? `${frontInfo.nation.name} 소속` : '재야';
  const isAdmin = auth?.user?.role === 'ADMIN';
  // 게임 전체 「점검 중」(보드 BAND_ORDER 맨 앞 · 전체 화면) — 판정은 lib/turnLoop isMaintenance 한 곳(임시: CLOSED). 입장 화면도 같다.
  // 운영자는 셸을 그대로 쓴다 — 서버를 닫고 여는 곳(관리 · 서버 상태)이 이 셸 안에 있다.
  const maintenance = closed && !isAdmin;
  const helpView = search?.get('help') ?? null;
  // 두 서랍 쿼리가 함께 오면(손으로 친 주소) 도움말이 이긴다 — 서랍 자리는 하나다.
  const mailView = helpView ? null : search?.get('mail') ?? null;
  // 한 서랍을 여는 주소는 다른 서랍 쿼리를 뺀다.
  const helpHref = withQuery(search, 'help', 'home', ['mail']);
  const mailHref = withQuery(search, 'mail', 'personal', ['help']);
  const router = useRouter();
  // 서랍이 열리면(주소에 ?help= · ?mail=) 계절 · 전체를 닫고, 계절 · 전체를 열면 서랍을 닫는다(두 쿼리를 뺀다).
  useEffect(() => {
    if (helpView || mailView) setOpen(null);
  }, [helpView, mailView]);
  const openLayer = useCallback((kind: 'menu' | 'season') => {
    setOpen(kind);
    if (helpView || mailView) router.replace(`${pathname}${withQuery(search, 'help', null, ['mail']).replace(/^\?$/, '')}`, { scroll: false });
  }, [helpView, mailView, pathname, router, search]);

  return (
    <div className={styles.frame} data-entry={entry || undefined} data-route={warRoom ? 'war-room' : undefined}>
      <header className={styles.top}>
        <CampaignLink slug="" className={styles.logo} aria-label="작전실로">
          <Brand size="small" />
        </CampaignLink>
        <h1 className={styles.title}>{entry ? '입장' : located?.group.label ?? '게임'}</h1>
        <span className={styles.chips}>
          {season ? (
            <button
              ref={seasonChip}
              type="button"
              className={`os-chip ${styles.chip} ${styles.seasonChip}`}
              aria-haspopup="dialog"
              aria-expanded={open === 'season'}
              aria-controls={open === 'season' ? SEASON_DIALOG_ID : undefined}
              onClick={() => (open === 'season' ? closeSeason() : openLayer('season'))}
            >
              <Icon name="season" size={16} className={styles.seasonGlyph} />
              <span>{season}</span>
              {hasSeasonNews() ? <span className={styles.seasonDot}><span className="sr-only">새 소식</span></span> : null}
            </button>
          ) : null}
          {/* 화면이 꽂는 칩 자리(모바일 작전실 「지난 순」) — 비면 접힌다. */}
          {!entry ? <span id={SHELL_PAGE_CHIPS_ID} className={styles.pageChips} /> : null}
          {!entry ? <span className={`os-chip ${styles.chip} ${styles.wide}`}>다음 개인 턴 {clock}</span> : null}
          {!entry ? (
            <Link className={`${styles.iconButton} ${styles.chipsEnd}`} href={mailHref} scroll={false} aria-label="서신">
              <ShellIcon name="mail" />
            </Link>
          ) : null}
          {/* 서랍은 쿼리만 바꾼다 — 문서를 다시 받지 않게(Link, 스크롤 유지). */}
          <Link className={styles.iconButton} href={helpHref} scroll={false} aria-label="이 화면 도움말">
            <ShellIcon name="help" />
          </Link>
          {entry ? <a className={`os-button os-button--ghost ${styles.lobby}`} href={LOBBY_HREF}>로비로</a> : null}
          {!entry && generalName ? (
            <CampaignLink slug="retinue" className={`${styles.who} ${styles.wide}`}>{`${generalName} · ${allegiance}`}</CampaignLink>
          ) : null}
          {!entry && hasGeneral ? <Chip tone="bronze" className={styles.wide}>{`명망 ${renown ?? '—'}`}</Chip> : null}
        </span>
        {open === 'season' && viewport !== 'mobile' ? (
          <SeasonPopover chip={seasonChip} month={month} phase={frontInfo?.global.turnPhase} onClose={closeSeason} onDismiss={dismissSeason} />
        ) : null}
      </header>
      {!entry && !maintenance ? <NoticeBand band={view?.band ?? null} onRecheck={recheck} /> : null}
      <div className={styles.body}>
        {/* 점검이면 화면을 내리고(닫힌 서버를 부르지 않게) 전체 화면 「점검 중」만 — 끝나면 화면이 새로 올라온다. */}
        {maintenance ? <MaintenanceMain /> : null}
        {!entry && !maintenance ? (
          <nav className={styles.rail} aria-label="게임 메뉴">
            {NAV31.map((group) => (
              <GroupLink key={group.key} group={group} current={located?.group.key === group.key} className={styles.railItem} />
            ))}
            <span className={styles.railGap} aria-hidden="true" />
            <Link className={styles.railItem} href={helpHref} scroll={false}>
              <ShellIcon name="help" />
              <span>도움말</span>
            </Link>
            {isAdmin ? (
              <CampaignLink slug="admin" className={styles.railItem}>
                <ShellIcon name="admin" />
                <span>관리</span>
              </CampaignLink>
            ) : null}
          </nav>
        ) : null}
        {!maintenance ? <main className={styles.main} aria-label="게임 콘텐츠">{children}</main> : null}
        {helpView ? (
          <HelpDrawer view={helpView} closeHref={withQuery(search, 'help', null)} groupKey={located?.group.key ?? null} screenPath={located?.screen?.path ?? null} />
        ) : null}
        {/* 서신 서랍 — 도움말 서랍과 같은 자리 · 같은 층(<main> 뒤라 같은 층에서 DOM 순서로 위). */}
        {mailView ? (
          <aside className={styles.drawer} aria-label="서신 서랍" data-mail-view={mailView}>
            <Suspense fallback={<StatusView kind="loading" rows={6} />}>
              <MailDrawer view={mailView} closeHref={`${pathname}${withQuery(search, 'mail', null).replace(/^\?$/, '')}`} />
            </Suspense>
          </aside>
        ) : null}
      </div>
      {!entry && !maintenance ? (
        <nav className={styles.tabbar} aria-label="게임 메뉴">
          {MOBILE_TAB_KEYS.map((key) => {
            const group = NAV31.find((g) => g.key === key)!;
            return <GroupLink key={key} group={group} current={located?.group.key === key} className={styles.tab} />;
          })}
          <button type="button" className={styles.tab} aria-haspopup="dialog" aria-expanded={open === 'menu'} onClick={() => openLayer('menu')}>
            <ShellIcon name="menu" />
            <span>전체</span>
          </button>
        </nav>
      ) : null}
      {open === 'menu' ? <MenuSheet current={located?.group.key ?? null} isAdmin={isAdmin} helpHref={helpHref} onClose={() => setOpen(null)} /> : null}
      {open === 'season' && viewport === 'mobile' ? <SeasonSheet month={month} phase={frontInfo?.global.turnPhase} onClose={closeSeason} onDismiss={dismissSeason} /> : null}
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
function MenuSheet({ current, isAdmin, helpHref, onClose }: {
  readonly current: string | null; readonly isAdmin: boolean; readonly helpHref: string; readonly onClose: () => void;
}) {
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
          {/* 모바일은 레일이 없다 — 도움말은 머리줄 「?」와 여기서 연다(서랍은 머리줄 아래 ~ 탭 막대 위 시트). */}
          <Link className={styles.sheetItem} href={helpHref} scroll={false} onClick={onClose}>도움말</Link>
          <a className={styles.sheetItem} href={LOBBY_HREF}>로비로</a>
          {isAdmin ? <CampaignLink slug="admin" className={styles.sheetItem} onClick={onClose}>관리</CampaignLink> : null}
        </div>
      </section>
    </div>
  );
}

type SeasonProps = {
  readonly month: number | null | undefined;
  readonly phase: number | null | undefined;
  readonly onClose: () => void;
};

/**
 * 계절 패널 — 데스크톱 · 태블릿(≥ 768). 머리줄 아래 떠 있는 패널 400(--z-float 「떠 있는 카드」, 비모달).
 * 투명 덮개를 깔지 않는다(지도 휠 · 끌기를 먹는다) — 바깥 누름은 document pointerdown 으로 본다(패널 · 칩 안은 뺀다).
 */
function SeasonPopover({ chip, month, phase, onClose, onDismiss }: SeasonProps & {
  readonly chip: RefObject<HTMLButtonElement | null>;
  /** 바깥 누름 — 닫기만 하고 초점은 누른 곳에 둔다. */
  readonly onDismiss: () => void;
}) {
  const panel = useRef<HTMLElement>(null);
  useEscape(panel, onClose, onDismiss);
  useEffect(() => {
    const onDown = (event: PointerEvent) => {
      const target = event.target as Node | null;
      if (!target || panel.current?.contains(target) || chip.current?.contains(target)) return;
      onDismiss();
    };
    document.addEventListener('pointerdown', onDown);
    return () => document.removeEventListener('pointerdown', onDown);
  }, [chip, onDismiss]);
  return (
    <section ref={panel} id={SEASON_DIALOG_ID} className={styles.seasonPop} role="dialog" aria-labelledby={SEASON_TITLE_ID}>
      <SeasonPanel month={month} phase={phase} onClose={onClose} titleId={SEASON_TITLE_ID} />
    </section>
  );
}

/** 계절 패널 — 모바일(< 768). 「전체」 메뉴와 같은 하단 시트 층(--z-sheet, 탭 막대를 가린다). */
function SeasonSheet({ month, phase, onClose, onDismiss }: SeasonProps & { readonly onDismiss: () => void }) {
  const sheet = useRef<HTMLElement>(null);
  useEscape(sheet, onClose, onDismiss);
  return (
    <div className={styles.sheetLayer}>
      <button type="button" className={styles.scrim} aria-label="계절 닫기" onClick={onClose} />
      <section
        ref={sheet}
        id={SEASON_DIALOG_ID}
        className={styles.sheet}
        role="dialog"
        aria-labelledby={SEASON_TITLE_ID}
      >
        <SeasonPanel month={month} phase={phase} onClose={onClose} titleId={SEASON_TITLE_ID} />
      </section>
    </div>
  );
}

/**
 * 열려 있는 동안 Esc 로 닫는다 — 패널 안 초점 못 받는 곳(달력 · 글자)을 눌러 초점이 body 로 빠져도 듣는다.
 * 초점이 패널 · 시트 밖 다른 누를 것 · 입력칸에 있으면(키보드로 나간 경우) 닫기만 하고 초점은 그대로 둔다.
 * 한글 등 조합 중 Esc 는 조합 취소라 닫지 않는다.
 */
function useEscape(inside: RefObject<HTMLElement | null>, onClose: () => void, onDismiss: () => void) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.isComposing) return;
      const active = document.activeElement;
      const away = active !== null && active !== document.body && !inside.current?.contains(active);
      (away ? onDismiss : onClose)();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [inside, onClose, onDismiss]);
}

/** 지금 쿼리에 한 값을 넣거나(값) 빼서(null) 만든 `?…` 주소. 경로는 그대로다. `drop` 쿼리는 함께 뺀다(서랍은 한 번에 하나). */
function withQuery(search: URLSearchParams | null, key: string, value: string | null, drop: readonly string[] = []): string {
  const next = new URLSearchParams(search?.toString() ?? '');
  for (const other of drop) next.delete(other);
  if (value === null) next.delete(key);
  else next.set(key, value);
  const text = next.toString();
  return text ? `?${text}` : '?';
}

/** 게임 전체 「점검 중」(보드 V31SystemMMaint) — 일정(시작 · 길이)은 서버 값이 없어 적지 않는다. 「공지 보기」는 갈 곳이 생기면 잇는다. */
function MaintenanceMain() {
  return (
    <main className={styles.main} aria-label="게임 콘텐츠">
      <StatusView
        kind="maintenance"
        scope="page"
        body="점검하는 동안 턴이 돌지 않고, 걸어 둔 예약은 그대로 남습니다. 끝나면 이 화면이 저절로 바뀝니다."
        actions={<a className="os-button os-status__action" href={LOBBY_HREF}><Icon name="lobby" size={16} />로비로</a>}
      />
    </main>
  );
}
