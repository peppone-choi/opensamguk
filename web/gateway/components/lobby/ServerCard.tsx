'use client';

import { useCallback, useEffect, useId, useState } from 'react';
import { Button, Chip, Portrait } from '@opensamguk/ui';
import MapPreview, { type MapData } from '@/components/MapPreview';
import NationStatusPanel, { type PreviewState } from '@/components/status/NationStatusPanel';
import StateLine from '@/components/status/StateLine';
import WorldEventsPanel from '@/components/status/WorldEventsPanel';
import { GAME_URL } from '@/lib/constants';
import { lobbyVerdict, type BasicInfo, type LobbyVerdict } from '@/lib/lobbyEntry';
import { resolveServerGamePath } from '@/lib/serverGameUrl';
import { serverLabel } from '@/lib/serverStatus';

export interface LobbyServer {
    readonly id: string;
    readonly name: string;
    readonly generation?: number | null;
    readonly gameUrl?: string;
}

const STATE_CHIP: Record<Exclude<LobbyVerdict['kind'], 'loading'>, { readonly label: string; readonly tone: 'moss' | 'rust' | 'info' | 'neutral' }> = {
    noResponse: { label: '응답 없음', tone: 'rust' },
    maintenance: { label: '점검 중', tone: 'rust' },
    preOpen: { label: '준비 중', tone: 'info' },
    joined: { label: '참가 중', tone: 'moss' },
    seasonEnded: { label: '시즌 끝', tone: 'neutral' },
    full: { label: '마감', tone: 'neutral' },
    recruiting: { label: '모집 중', tone: 'moss' },
};

/**
 * 로비 서버 카드(설계서 P-G04 판정 표 · LB9–LB28). 자기 basic-info 를 한 번 부르고, 실패하면 그 카드만 「응답 없음」 + 다시 시도.
 * 「현황 펼치기」를 누르면 그 서버의 지도 · 세력 현황 · 천하 정세를 연다(한 번에 한 서버 — 서버마다 지도 전체를 받지 않는다).
 */
export default function ServerCard({ server, onVerdict, expanded, onToggle }: {
    readonly server: LobbyServer;
    readonly onVerdict: (id: string, verdict: LobbyVerdict) => void;
    readonly expanded: boolean;
    readonly onToggle: (id: string) => void;
}) {
    const [state, setState] = useState<{ loading: boolean; info: BasicInfo | null }>({ loading: true, info: null });
    const [attempt, setAttempt] = useState(0);
    const [preview, setPreview] = useState<PreviewState>({ kind: 'loading' });
    const [mapAttempt, setMapAttempt] = useState(0);
    const statusId = useId();
    const verdict = lobbyVerdict(state.loading, state.info);
    const label = serverLabel(server);

    useEffect(() => {
        onVerdict(server.id, verdict);
        // 판정의 종류가 바뀔 때만 알린다(객체는 매번 새로 만들어진다).
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [verdict.kind, server.id]);

    useEffect(() => {
        let alive = true;
        setState({ loading: true, info: null });
        fetch(`/api/server-basic-info/${encodeURIComponent(server.id)}`, { cache: 'no-store' })
            .then((response) => (response.ok ? response.json() : Promise.reject(new Error(String(response.status)))))
            .then((data: BasicInfo) => { if (alive) setState({ loading: false, info: data }); })
            .catch(() => { if (alive) setState({ loading: false, info: null }); });
        return () => { alive = false; };
    }, [server.id, attempt]);

    const onPreview = useCallback((data: MapData) => setPreview({ kind: 'ready', data }), []);
    const onPreviewError = useCallback(() => setPreview({ kind: 'error' }), []);
    const game = state.info?.game ?? null;
    const gamePath = resolveServerGamePath(server.gameUrl, server.id, GAME_URL);

    let action: React.ReactNode = null;
    switch (verdict.kind) {
        case 'noResponse':
            action = <button type="button" className="os-button os-button--ghost gw31-card__action" onClick={() => setAttempt((n) => n + 1)}>다시 시도</button>;
            break;
        case 'maintenance':
            action = <Button block className="gw31-card__action" disabled reason="점검 중입니다. 끝나면 다시 들어올 수 있습니다">입장</Button>;
            break;
        case 'preOpen':
            // 열리는 시각(K3-03 openAt)이 아직 오지 않는다 — 시각을 지어내지 않는다.
            action = <Button block className="gw31-card__action" disabled reason="아직 열리지 않았습니다. 열리는 시각은 공지를 보세요">입장</Button>;
            break;
        case 'joined':
            action = <a href={gamePath} className="os-button os-button--primary gw31-card__action">입장</a>;
            break;
        case 'full':
            action = <Button block className="gw31-card__action" disabled reason={verdict.reason}>장수 만들기</Button>;
            break;
        case 'recruiting':
            action = <a href={gamePath} className="os-button os-button--primary gw31-card__action">장수 만들기</a>;
            break;
        default:
            action = null; // 불러오는 중 · 시즌 끝(결산 보기는 P-H05 · K8 뒤)
    }

    return (
        <article className="os-panel os-panel--static gw31-card" aria-labelledby={`${statusId}-title`} data-verdict={verdict.kind}>
            <div className="gw31-card__body">
                <div className="gw31-card__info">
                    <div className="gw31-card__titlerow">
                        <h3 id={`${statusId}-title`} className="gw31-card__title os-serif">{server.name}</h3>
                        {server.generation != null && <Chip tone="bronze">{server.generation}기</Chip>}
                        {verdict.kind !== 'loading' && <Chip tone={STATE_CHIP[verdict.kind].tone}>{STATE_CHIP[verdict.kind].label}</Chip>}
                        {verdict.kind === 'seasonEnded' && verdict.unified && <Chip tone="bronze">통일</Chip>}
                        {game?.catchUp?.active && <Chip tone="info">따라잡는 중 · {game.catchUp.multiplier}배속</Chip>}
                    </div>
                    {verdict.kind === 'loading' && <StateLine kind="loading" title="서버 현황을 불러오는 중" />}
                    {verdict.kind === 'noResponse' && <p className="gw31-card__line gw31-card__line--bad">현황을 받지 못했습니다.</p>}
                    {game && (
                        <>
                            <p className="gw31-card__line">{game.year}년 {game.month}월{game.turnPhaseText ? ` ${game.turnPhaseText}` : ''} · <span className="gw31-card__scenario">{game.scenario}</span></p>
                            <p className="gw31-card__line os-num">세력 {game.nationCnt} · 사람 {game.userCnt} / {game.maxUserCnt} · NPC {game.npcCnt}</p>
                            <p className="gw31-card__line gw31-card__line--muted">한 순 {game.turnTerm}분</p>
                        </>
                    )}
                </div>
                <div className="gw31-card__side">
                    <span className="gw31-card__label">내 장수</span>
                    {verdict.kind === 'joined' ? (
                        <span className="gw31-card__me">
                            <Portrait picture={verdict.me.picture} imageServer={verdict.me.imageServer} size="card-56" alt={verdict.me.name} />
                            <strong>{verdict.me.name}</strong>
                        </span>
                    ) : (
                        <span className="gw31-card__none">{verdict.kind === 'loading' ? '' : '내 장수 없음'}</span>
                    )}
                    {action}
                </div>
            </div>
            <button
                type="button"
                className="os-button os-button--ghost gw31-card__toggle"
                aria-expanded={expanded}
                aria-controls={`${statusId}-status`}
                onClick={() => { if (!expanded) setPreview({ kind: 'loading' }); onToggle(server.id); }}
            >
                {expanded ? '현황 접기' : '현황 펼치기'}
            </button>
            {expanded && (
                <div id={`${statusId}-status`} className="gw31-card__status">
                    <div className="gw31-card__map">
                        <MapPreview serverId={server.id} serverName={label} refreshKey={mapAttempt} onPreview={onPreview} onPreviewError={onPreviewError} />
                    </div>
                    <NationStatusPanel serverId={server.id} serverLabel={label} preview={preview} onRetry={() => { setPreview({ kind: 'loading' }); setMapAttempt((n) => n + 1); }} />
                    <WorldEventsPanel serverId={server.id} preview={preview.kind === 'ready' ? preview.data : null} />
                </div>
            )}
        </article>
    );
}
