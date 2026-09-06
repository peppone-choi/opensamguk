'use client';

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import Link from 'next/link';
import AuthGate from '@/components/AuthGate';
import Topbar from '@/components/Topbar';
import ServerBoard from '@/components/ServerBoard';
import NoticeBoard from '@/components/NoticeBoard';
import { Chip, Portrait } from '@opensamguk/ui';
import { useAuthOptional } from '@/lib/auth-context';
import { ENTRY_FILTERS, entryStateOf, matchesFilter, type EntryFilter, type EntryState } from '@/lib/lobbyEntry';
import { AUTH_LABELS, GAME_URL, LOBBY_LABELS, LOBBY_FOOTNOTES } from '@/lib/constants';
import { resolveServerGamePath } from '@/lib/serverGameUrl';

// servers.json 은 이제 **라우팅 정보만**(id/name/gameUrl) 쓴다. 상태/턴텀/유저수는 전부 라이브
// basic-info 결과로 그린다(하드코딩 제거 — 진입 플로우 정본화 §5.4). gameApiUrl 은 서버사이드
// resolveGameApiOrigin 이 쓰므로 클라엔 불필요.
interface ServerEntry {
    id: string;
    name: string;
    generation?: number;
    gameUrl?: string;
}

// game-api ServerBasicInfoResponse 미러(IdentityDto/ServerBasicInfoDto). 전 필드 실쿼리 결과.
interface ServerGameInfo {
    isUnited: number;
    npcMode: number;
    year: number;
    month: number;
    turnPhase?: number | null;
    turnPhaseText?: string | null;
    scenario: string;
    maxUserCnt: number;
    turnTerm: number;
    userCnt: number;
    npcCnt: number;
    nationCnt: number;
    fictionMode: string;
    joinMode: string | null;
    blockGeneralCreate: number;
    defaultStatTotal: number;
    otherTextInfo: string;
    status: string;
}
interface ServerMeInfo {
    name: string;
    picture: string | null;
    imageServer: number;
}
interface ServerBasicInfo {
    game: ServerGameInfo | null;
    me: ServerMeInfo | null;
}

/** n_country 라벨(entrance.ts L233-248) — isUnited 상태 / 평시는 <N국 경쟁중>. */
function nCountryLabel(game: ServerGameInfo): string {
    switch (game.isUnited) {
        case 3:
            return '§이벤트 종료§';
        case 1:
            return '§이벤트 진행중§';
        case 2:
            return '§천하통일§';
        default:
            return `<${game.nationCnt}국 경쟁중>`;
    }
}

type RowState = { loading: boolean; info: ServerBasicInfo | null };
function possessionEntryHref(href: string): string {
    const hashIndex = href.indexOf('#');
    const base = hashIndex === -1 ? href : href.slice(0, hashIndex);
    const hash = hashIndex === -1 ? '' : href.slice(hashIndex);
    const queryIndex = base.indexOf('?');
    const path = queryIndex === -1 ? base : base.slice(0, queryIndex);
    const params = new URLSearchParams(queryIndex === -1 ? '' : base.slice(queryIndex + 1));

    params.set('entry', 'possession');
    return `${path}?${params.toString()}${hash}`;
}

/**
 * 한 서버 행 — 자기 basic-info 를 fan-out 호출해 진입 상태머신(entrance.ts)을 그린다. 실패해도 그 행만
 * '폐쇄 중'으로 graceful degrade(다른 행/페이지 영향 없음). 게이트:
 *   me && me.name        → 입장
 *   userCnt >= maxUserCnt → 장수 등록 마감
 *   그 외               → 미등록 + 진입(장수 등록) 버튼
 */
function ServerRow({ server, filter, onState }: { server: ServerEntry; filter: EntryFilter; onState?: (id: string, state: EntryState) => void }) {
    const [state, setState] = useState<RowState>({ loading: true, info: null });
    const entry = entryStateOf(state.loading, state.info);
    useEffect(() => {
        onState?.(server.id, entry);
    }, [entry, onState, server.id]);

    useEffect(() => {
        let alive = true;
        fetch(`/api/server-basic-info/${server.id}`, { cache: 'no-store' })
            .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((data: ServerBasicInfo) => {
                if (alive) setState({ loading: false, info: data });
            })
            .catch(() => {
                if (alive) setState({ loading: false, info: null });
            });
        return () => {
            alive = false;
        };
    }, [server.id]);

    const { loading, info } = state;
    const game = info?.game ?? null;
    const me = info?.me ?? null;
    const gameUrl = resolveServerGamePath(server.gameUrl, server.id, GAME_URL);

    // 정보 셀.
    let infoCell: ReactNode;
    if (loading) {
        infoCell = <span className="text-muted">불러오는 중…</span>;
    } else if (!game) {
        infoCell = <span className="text-muted">{LOBBY_LABELS.closed}</span>;
    } else {
        infoCell = (
            <div className="lobby-server-info">
                <div>
                    <strong>{nCountryLabel(game)}</strong>
                </div>
                <div>
                    서기 {game.year}년 {game.month}월{game.turnPhaseText ? ` ${game.turnPhaseText}` : ''}{' '}
                    <span style={{ color: 'var(--accent-gold, orange)' }}>{game.scenario}</span>
                </div>
                <div>
                    유저 {game.userCnt} / {game.maxUserCnt}명{' '}
                    <span style={{ color: 'cyan' }}>NPC {game.npcCnt}명</span> ({game.turnTerm}분 턴 서버)
                </div>
                <div className="text-muted">
                    (상성: {game.fictionMode}), (기타: {game.otherTextInfo})
                </div>
            </div>
        );
    }

    // 캐릭터 셀 + 선택 셀 게이트.
    let characterCell: ReactNode;
    let selectCell: ReactNode;
    if (loading) {
        characterCell = <span className="text-muted">…</span>;
        selectCell = <span className="text-muted">…</span>;
    } else if (!game) {
        characterCell = '-';
        selectCell = LOBBY_LABELS.closed;
    } else if (me && me.name) {
        characterCell = (
            <span className="lobby-character">
                <Portrait picture={me.picture} imageServer={me.imageServer} size="card-56" alt={me.name} />
                <strong>{me.name}</strong>
            </span>
        );
        selectCell = (
            <a href={gameUrl} target="_self" className="btn-enter">
                {LOBBY_LABELS.enter}
            </a>
        );
    } else if (game.userCnt >= game.maxUserCnt) {
        // 등록 마감 — 여석 없음.
        characterCell = <span className="text-muted">-</span>;
        selectCell = LOBBY_LABELS.registerClosed;
    } else {
        const canCreate = (game.blockGeneralCreate & 1) === 0;
        const canSelectNpc = game.npcMode === 1;
        const canSelectPool = game.npcMode === 2;
        characterCell = <span className="text-muted">{LOBBY_LABELS.unregistered}</span>;
        selectCell = (
            <span style={{ display: 'inline-flex', gap: '0.4rem', flexWrap: 'wrap' }}>
                {canCreate && (
                    <a
                        href={resolveServerGamePath(gameUrl, server.id, GAME_URL, 'join')}
                        target="_self"
                        className="btn-enter"
                    >
                        {LOBBY_LABELS.createGeneral}
                    </a>
                )}
                {canSelectNpc && (
                    <a href={possessionEntryHref(gameUrl)} target="_self" className="btn-enter">
                        {LOBBY_LABELS.possessGeneral}
                    </a>
                )}
                {canSelectPool && (
                    <a
                        href={resolveServerGamePath(gameUrl, server.id, GAME_URL, 'select-pool')}
                        target="_self"
                        className="btn-enter"
                    >
                        {LOBBY_LABELS.selectGeneral}
                    </a>
                )}
                {/* 셋 다 비활성(불가 서버)이면 등록 경로 없음 — legacy 동일(빈 셀). */}
                {!canCreate && !canSelectNpc && !canSelectPool && (
                    <span className="text-muted">-</span>
                )}
            </span>
        );
    }

    if (!matchesFilter(entry, filter)) return null;
    return (
        <tr data-entry-state={entry}>
            <td data-label={LOBBY_LABELS.colServer}>
                <span className="lobby-server-name">{server.name}</span>
                {server.generation != null && <Chip tone="bronze">{server.generation}기</Chip>}
                {entry === 'joined' && <Chip tone="moss">참가 중</Chip>}
                {entry === 'ended' && <Chip>종료</Chip>}
            </td>
            <td data-label={LOBBY_LABELS.colInfo}>{infoCell}</td>
            <td data-label={LOBBY_LABELS.colCharacter}>{characterCell}</td>
            <td data-label={LOBBY_LABELS.colSelect}>{selectCell}</td>
        </tr>
    );
}

function LobbyView() {
  const auth = useAuthOptional();
  const user = auth?.user ?? null;
  const [servers, setServers] = useState<ServerEntry[] | null>(null);
  const [filter, setFilter] = useState<EntryFilter>('all');
  const [entryStates, setEntryStates] = useState<Record<string, EntryState>>({});
  const onRowState = useCallback((id: string, state: EntryState) => {
    setEntryStates((cur) => (cur[id] === state ? cur : { ...cur, [id]: state }));
  }, []);
  const visibleCount = servers ? servers.filter((s) => matchesFilter(entryStates[s.id] ?? 'loading', filter)).length : 0;

  useEffect(() => {
    let alive = true;
    fetch('/api/servers', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((data: { servers?: ServerEntry[] }) => {
        if (alive) setServers(data.servers ?? []);
      })
      .catch(() => {
        if (alive) setServers([]);
      });
    return () => {
      alive = false;
    };
  }, []);

  return (
    <div className="lobby-shell">
      <Topbar current="lobby" />
      <main className="lobby-main lobby-grid fade-in">
        <div className="lobby-primary">
          <h1 className="lobby-section-title os-serif">게임 로비</h1>
          <section className="os-panel os-panel--static lobby-servers" aria-label={LOBBY_LABELS.serverSelect}>
            <div className="os-section-header">
              <span className="os-section-header__bar" aria-hidden="true" />
              <h2 className="lobby-section-title os-section-header__title">{LOBBY_LABELS.serverSelect}</h2>
              <span className="os-section-header__sub lobby-servers__hint">내 장수가 있는 서버는 바로 입장하고, 없는 서버는 장수를 만들어 시작합니다.</span>
              <span className="os-section-header__spacer" />
              <div className="os-pill-tabs" role="tablist" aria-label="서버 필터">
                {ENTRY_FILTERS.map((f) => (
                  <button key={f.key} type="button" role="tab" aria-selected={filter === f.key} className={filter === f.key ? 'os-pill-tabs__on' : undefined} onClick={() => setFilter(f.key)}>
                    {f.label}
                  </button>
                ))}
              </div>
            </div>
            {servers === null || servers.length === 0 ? (
              <p className="text-muted lobby-servers__empty" role="status" aria-live="polite">
                {servers === null
                  ? '서버 목록을 불러오는 중입니다.'
                  : '현재 이용할 수 있는 게임 서버가 없습니다.'}
              </p>
            ) : (
              <div className="game-table-wrap">
                <table className="game-table os-table lobby-table">
                  <caption className="sr-only">{LOBBY_LABELS.serverSelect}</caption>
                  <thead>
                    <tr>
                      <th>{LOBBY_LABELS.colServer}</th>
                      <th>{LOBBY_LABELS.colInfo}</th>
                      <th>{LOBBY_LABELS.colCharacter}</th>
                      <th>{LOBBY_LABELS.colSelect}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {servers.map((server) => (
                      <ServerRow key={server.id} server={server} filter={filter} onState={onRowState} />
                    ))}
                  </tbody>
                </table>
                {visibleCount === 0 && filter !== 'all' && (
                  <p className="text-muted lobby-servers__empty" role="status">이 조건에 맞는 서버가 없습니다.</p>
                )}
              </div>
            )}
            <p className="lobby-servers__note">서버 설정에 따라 생성·빙의·선택 중 가능한 진입만 보입니다. 현황을 못 받은 서버는 「{LOBBY_LABELS.closed}」으로 남깁니다.</p>
          </section>
          {/* 서버 전환 탭 + 선택 서버 세계지도 현황 + 세력 현황 + 전황 로그 (devsam '제 전황' 형태). */}
          <ServerBoard />
          <section className="os-panel os-panel--static lobby-account" aria-label={LOBBY_LABELS.accountSection}>
            <div className="os-section-header">
              <span className="os-section-header__bar" aria-hidden="true" />
              <h2 className="lobby-section-title os-section-header__title">{LOBBY_LABELS.accountSection}</h2>
            </div>
            <div className="lobby-account-actions">
              <Link className="os-button os-button--ghost btn-ghost" href="/account">
                {LOBBY_LABELS.accountManage}
              </Link>
              <Link className="os-button os-button--ghost btn-ghost" href="/board">커뮤니티 게시판</Link>
              {user?.role === 'ADMIN' && (
                <Link className="os-button os-button--ghost btn-ghost" href="/admin">{LOBBY_LABELS.admin} (ADMIN만)</Link>
              )}
              {auth && (
                <button type="button" className="os-button os-button--ghost" onClick={() => void auth.logout()}>
                  {AUTH_LABELS.logout}
                </button>
              )}
            </div>
          </section>
          <ul className="footnotes">
            {LOBBY_FOOTNOTES.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </div>
        <aside className="lobby-side" aria-label="공지">
          <NoticeBoard />
        </aside>
      </main>
    </div>
  );
}

export default function LobbyPage() {
  return (
    <AuthGate>
      <LobbyView />
    </AuthGate>
  );
}
