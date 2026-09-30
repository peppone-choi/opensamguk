'use client';

import { useCallback, useMemo, useState } from 'react';
import { Chip } from '@opensamguk/ui';
import { useRouter } from 'next/navigation';
import MemberHeader from '@/components/gateway/MemberHeader';
import NoticeBoard from '@/components/NoticeBoard';
import StateLine from '@/components/status/StateLine';
import { LOBBY_FILTERS, matchesFilter, type LobbyFilter, type LobbyVerdict } from '@/lib/lobbyEntry';
import PracticeCard from './PracticeCard';
import ServerCard, { type LobbyServer } from './ServerCard';

/** 로비 각주(LB37 · LB38) — 공개 알파 문구(U5)와 함께 따로 승인한다. 그 전까지 초안 표시. */
const FOOTNOTES = [
    { text: '한 사람이 계정 여러 개를 쓰거나 남의 턴을 대신 넣으면 이용이 막힐 수 있습니다.', warn: true },
    { text: '계정은 한 번 만들면 계속 씁니다. 서버가 새로 시작하면 장수만 다시 만듭니다.', warn: false },
] as const;

/**
 * P-G04 로비(설계서 §2.4, 보드 V31K5Lobby · MLobby · LobbyStates). 서버 목록은 서버 렌더가 준다(`/api/servers` 를 두 번
 * 부르던 것 — 표 + 서버 보드 — 을 없앴다, LB43). 카드마다 basic-info 한 번, 현황은 펼친 서버 하나만 연다.
 */
export default function LobbyScreen({ servers, registry }: {
    readonly servers: readonly LobbyServer[];
    readonly registry: 'ok' | 'error';
}) {
    const router = useRouter();
    const [filter, setFilter] = useState<LobbyFilter>('all');
    const [verdicts, setVerdicts] = useState<Record<string, LobbyVerdict>>({});
    const [expanded, setExpanded] = useState<string | null>(null);
    const onVerdict = useCallback((id: string, verdict: LobbyVerdict) => {
        setVerdicts((current) => (current[id]?.kind === verdict.kind ? current : { ...current, [id]: verdict }));
    }, []);
    const toggle = useCallback((id: string) => setExpanded((current) => (current === id ? null : id)), []);
    const visible = useMemo(
        () => servers.filter((server) => matchesFilter(verdicts[server.id] ?? { kind: 'loading' }, filter)),
        [servers, verdicts, filter],
    );

    return (
        <div className="gw31-page">
            <MemberHeader current="lobby" />
            <main className="gw31-lobby">
                <div className="gw31-lobby__main">
                    <div className="gw31-lobby__head">
                        <h1 className="gw31-lobby__title os-serif">게임 로비</h1>
                        <p className="gw31-card__line gw31-card__line--muted">내 장수가 있는 서버는 바로 입장하고, 없는 서버는 장수를 만들어 시작합니다.</p>
                    </div>
                    <div className="gw31-chips gw31-lobby__filters" role="group" aria-label="서버 거르기">
                        {LOBBY_FILTERS.map((item) => (
                            <button
                                key={item.key}
                                type="button"
                                className={`os-button gw31-btn gw31-chip-btn${filter === item.key ? ' is-on' : ''}`}
                                aria-pressed={filter === item.key}
                                onClick={() => setFilter(item.key)}
                            >
                                {item.label}
                            </button>
                        ))}
                    </div>
                    <PracticeCard />
                    <section className="gw31-lobby__list" aria-label="서버">
                        {registry === 'error' && <StateLine kind="error" title="서버 목록을 불러오지 못했습니다" onRetry={() => router.refresh()} />}
                        {registry === 'ok' && servers.length === 0 && <StateLine kind="empty" title="현재 이용할 수 있는 게임 서버가 없습니다." />}
                        {servers.map((server) => (
                            <div key={server.id} hidden={!visible.includes(server)}>
                                <ServerCard server={server} onVerdict={onVerdict} expanded={expanded === server.id} onToggle={toggle} />
                            </div>
                        ))}
                        {servers.length > 0 && visible.length === 0 && <StateLine kind="empty" title="이 조건에 맞는 서버가 없습니다." />}
                        <p className="gw31-card__line gw31-card__line--muted">현황을 받지 못한 서버는 「응답 없음」으로 보입니다.</p>
                    </section>
                </div>
                <aside className="gw31-lobby__side" aria-label="공지와 안내">
                    <NoticeBoard />
                    <ul className="gw31-foot-notes">
                        {FOOTNOTES.map((note) => (
                            <li key={note.text} className={note.warn ? 'is-warn' : undefined} data-copy-status="draft">{note.text}</li>
                        ))}
                    </ul>
                    <span className="gw31-intro__draft"><Chip tone="info">문구 초안 — 공개 알파 문구와 함께 승인</Chip></span>
                </aside>
            </main>
        </div>
    );
}
