'use client';

import { useEffect, useState, type ReactNode } from 'react';
import AuthGate from '@/components/AuthGate';
import Topbar from '@/components/Topbar';
import ServerBoard from '@/components/ServerBoard';
import { GAME_URL, LOBBY_LABELS, LOBBY_FOOTNOTES } from '@/lib/constants';

// servers.json 은 이제 **라우팅 정보만**(id/name/gameUrl) 쓴다. 상태/턴텀/유저수는 전부 라이브
// basic-info 결과로 그린다(하드코딩 제거 — 진입 플로우 정본화 §5.4). gameApiUrl 은 서버사이드
// resolveGameApiOrigin 이 쓰므로 클라엔 불필요.
interface ServerEntry {
    id: string;
    name: string;
    gameUrl?: string;
}

// game-api ServerBasicInfoResponse 미러(IdentityDto/ServerBasicInfoDto). 전 필드 실쿼리 결과.
interface ServerGameInfo {
    isUnited: number;
    npcMode: number;
    year: number;
    month: number;
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

/**
 * 한 서버 행 — 자기 basic-info 를 fan-out 호출해 진입 상태머신(entrance.ts)을 그린다. 실패해도 그 행만
 * '폐쇄 중'으로 graceful degrade(다른 행/페이지 영향 없음). 게이트:
 *   me && me.name        → 입장
 *   userCnt >= maxUserCnt → 장수 등록 마감
 *   그 외               → 미등록 + 진입(장수 등록) 버튼
 */
function ServerRow({ server }: { server: ServerEntry }) {
    const [state, setState] = useState<RowState>({ loading: true, info: null });

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
    const gameUrl = server.gameUrl || GAME_URL;

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
                    서기 {game.year}년 {game.month}월{' '}
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
        // 입장 — 이 서버에 소유 장수 보유.
        characterCell = <strong>{me.name}</strong>;
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
        // 미등록 — 여석 있음. devsam 은 canCreate/canSelectNPC/canSelectPool 로 장수생성/빙의/선택 3버튼을
        // 가르나(entrance.ts L270-279), opensamguk 의 v_join/select_npc/select_general 정본 페이지는 B1-B3
        // (골든 동반)에서 닫는다. 그 전까지는 게임 진입(CharacterClaim 빙의 경로)으로 통합한다.
        characterCell = <span className="text-muted">{LOBBY_LABELS.unregistered}</span>;
        selectCell = (
            <a href={gameUrl} target="_self" className="btn-enter">
                장수 등록
            </a>
        );
    }

    return (
        <tr>
            <td>{server.name}</td>
            <td>{infoCell}</td>
            <td>{characterCell}</td>
            <td>{selectCell}</td>
        </tr>
    );
}

function LobbyView() {
    const [servers, setServers] = useState<ServerEntry[]>([]);

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
            <Topbar />
            <main className="lobby-main fade-in">
                {/* 서버 전환 탭 + 선택 서버 세계지도 현황 + 전황 로그 (devsam '제 전황' 형태). */}
                <ServerBoard />

                {servers.length > 0 && (
                    <section>
                        <h2 className="lobby-section-title">{LOBBY_LABELS.serverSelect}</h2>
                        <div className="game-table-wrap">
                            <table className="game-table">
                                <caption>{LOBBY_LABELS.serverSelect}</caption>
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
                                        <ServerRow key={server.id} server={server} />
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </section>
                )}

                <section>
                    <h2 className="lobby-section-title">{LOBBY_LABELS.accountSection}</h2>
                    {/* 비밀번호/전콘/탈퇴 관리 — 백엔드 미구현(F0). 로그아웃은 상단바. */}
                    <button className="btn-ghost" disabled title="준비 중">
                        {LOBBY_LABELS.accountManage}
                    </button>
                </section>

                <ul className="footnotes">
                    {LOBBY_FOOTNOTES.map((note) => (
                        <li key={note}>{note}</li>
                    ))}
                </ul>
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
