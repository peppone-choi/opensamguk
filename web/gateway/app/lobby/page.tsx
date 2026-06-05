'use client';

import AuthGate from '@/components/AuthGate';
import Topbar from '@/components/Topbar';
import ServerBoard from '@/components/ServerBoard';
import {
    GAME_URL,
    LOBBY_LABELS,
    LOBBY_FOOTNOTES,
    SERVER_STATUS,
} from '@/lib/constants';
import serversData from '@/config/servers.json';

interface ServerEntry {
    id: string;
    name: string;
    status: string;
    turnterm: number;
    gameUrl?: string;
    gameApiUrl?: string;
}

const SERVERS = serversData.servers as ServerEntry[];

/** 알 수 없는 상태값도 안전하게 렌더 (legacy 폐쇄 fallback). */
function statusBadge(status: string): { label: string; cls: string } {
    return SERVER_STATUS[status] ?? SERVER_STATUS.closed;
}

function LobbyView() {
    return (
        <div className="lobby-shell">
            <Topbar />
            <main className="lobby-main fade-in">
                {/* 서버 전환 탭 + 선택 서버 세계지도 현황 + 전황 로그 (devsam '제 전황' 형태). */}
                <ServerBoard />

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
                                {SERVERS.map((server) => {
                                    const badge = statusBadge(server.status);
                                    const closed = server.status === 'closed';
                                    return (
                                        <tr key={server.id}>
                                            <td>{server.name}</td>
                                            <td>
                                                <span className={`status-badge ${badge.cls}`}>{badge.label}</span>{' '}
                                                {`(${server.turnterm}분 턴 서버)`}
                                            </td>
                                            {/* 캐릭터 셀: 서버별 장수 데이터는 P7(게임 서버)에서 — F0은 미등록 표시 */}
                                            <td>{LOBBY_LABELS.unregistered}</td>
                                            <td>
                                                {closed ? (
                                                    LOBBY_LABELS.closed
                                                ) : (
                                                    <a href={server.gameUrl || GAME_URL} target="_self">
                                                        {LOBBY_LABELS.enter}
                                                    </a>
                                                )}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                </section>

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
