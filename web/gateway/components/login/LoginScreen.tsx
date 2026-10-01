'use client';

import { Suspense, useCallback, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Chip } from '@opensamguk/ui';
import MapPreview, { type MapData } from '@/components/MapPreview';
import NoticeBoard from '@/components/NoticeBoard';
import PolicyLinks from '@/components/gateway/PolicyLinks';
import PublicHeader from '@/components/gateway/PublicHeader';
import NationStatusPanel, { type PreviewState } from '@/components/status/NationStatusPanel';
import ServerChips, { type ServerChoice } from '@/components/status/ServerChips';
import StateLine from '@/components/status/StateLine';
import WorldEventsPanel from '@/components/status/WorldEventsPanel';
import { previewCaption, serverLabel } from '@/lib/serverStatus';
import LoginForm from './LoginForm';

/** 소개 문구 — 승인됨 2026-10-01(D18). 지금 문장 그대로 쓴다. */
const LOGIN_LEAD = '장수 한 명으로 시작해 순마다 명령을 세우고, 전투가 열리면 직접 지휘한다.';

export type RegistryState = 'ok' | 'error';

/**
 * P-G02 로그인 + 서버 현황 지도(설계서 §2.2, 보드 V31K5Login · MLogin · MLoginScroll · LoginEmpty).
 * 지도가 주인공이다 — 데스크톱은 화면 전체 배경, 모바일은 위 480 띠. 폼과 서버 현황은 지도 위에 뜬 패널이다.
 * 지도 미리보기는 한 번만 부르고(MapPreview) 세력 현황 · 천하 정세 이름 풀이가 같은 자료를 쓴다.
 */
export default function LoginScreen({ servers, registry }: {
    readonly servers: readonly ServerChoice[];
    readonly registry: RegistryState;
}) {
    const router = useRouter();
    const [selectedId, setSelectedId] = useState<string | null>(servers[0]?.id ?? null);
    const [preview, setPreview] = useState<PreviewState>({ kind: 'loading' });
    const [mapAttempt, setMapAttempt] = useState(0);
    const selected = servers.find((server) => server.id === selectedId) ?? servers[0] ?? null;
    const label = selected ? serverLabel(selected) : '';

    // 서버를 바꾸거나 다시 부를 때 곧바로 「불러오는 중」으로 되돌린다. 효과(useEffect)로 되돌리면 자식 지도의
    // 효과가 먼저 돌아 받은 미리보기를 덮어쓴다(React 는 자식 효과를 먼저 돌린다).
    const selectServer = (id: string) => {
        if (id === selected?.id) return;
        setPreview({ kind: 'loading' });
        setSelectedId(id);
    };
    const onPreview = useCallback((data: MapData) => setPreview({ kind: 'ready', data }), []);
    const onPreviewError = useCallback(() => setPreview({ kind: 'error' }), []);
    const retryMap = useCallback(() => {
        setPreview({ kind: 'loading' });
        setMapAttempt((n) => n + 1);
    }, []);

    return (
        <div className="gw31-login">
            <div className="gw31-login__map">
                {selected ? (
                    <MapPreview
                        key={selected.id}
                        variant="backdrop"
                        serverId={selected.id}
                        serverName={label}
                        refreshKey={mapAttempt}
                        onPreview={onPreview}
                        onPreviewError={onPreviewError}
                    />
                ) : (
                    // 서버가 없으면 세력 없는 기본 지형을 깐다(설계서 SB6 · K2-01 설계 층 export 대기) — 그 전까지는 바탕색만.
                    <div className="gw31-login__terrain" aria-hidden="true" />
                )}
            </div>
            <PublicHeader action="join" overlay />
            <main className="gw31-login__stage">
                <section className="gw31-intro" aria-label="소개">
                    <img className="gw31-intro__wordmark" src="/logo-wordmark.png" alt="오픈삼국" width={420} height={157} decoding="async" fetchPriority="high" />
                    <h2 className="gw31-intro__title os-serif">한 명의 장수에서 천하까지.</h2>
                    <p className="gw31-intro__lead" data-copy-status="approved">{LOGIN_LEAD}</p>
                </section>
                <section className="os-panel os-panel--static gw31-login__card" id="login-form" aria-labelledby="login-title" tabIndex={-1}>
                    <h1 id="login-title" className="gw31-login__title os-serif">로그인</h1>
                    <Suspense fallback={<StateLine kind="loading" title="로그인 화면을 준비하는 중" />}>
                        <LoginForm />
                    </Suspense>
                    <a href="#server-status" className="gw31-link gw31-login__to-status">서버 현황 — 세력 · 천하 정세 · 공지</a>
                </section>
                <div className="gw31-login__status" id="server-status" aria-label="서버 현황">
                    <div className="gw31-login__chiprow">
                        {registry === 'error' && (
                            <StateLine kind="error" title="서버 목록을 불러오지 못했습니다" onRetry={() => router.refresh()} />
                        )}
                        {registry === 'ok' && servers.length === 0 && <StateLine kind="empty" title="지금 열린 서버가 없습니다" />}
                        {selected && <ServerChips servers={servers} selectedId={selected.id} onSelect={selectServer} />}
                        {selected && preview.kind === 'ready' && (
                            <Chip className="gw31-login__caption">{previewCaption(label, preview.data)}</Chip>
                        )}
                    </div>
                    {selected && (
                        <div className="gw31-login__panels">
                            <NationStatusPanel serverId={selected.id} serverLabel={label} preview={preview} onRetry={retryMap} className="gw31-login__nations" />
                            <WorldEventsPanel
                                serverId={selected.id}
                                preview={preview.kind === 'ready' ? preview.data : null}
                                className="gw31-login__events"
                                footer={<a href="#login-form" className="gw31-link gw31-panel__foot">더 보기 — 로그인하면 기록에서</a>}
                            />
                        </div>
                    )}
                </div>
                <NoticeBoard className="gw31-login__notices" />
                <footer className="gw31-login__foot">
                    <PolicyLinks />
                </footer>
            </main>
        </div>
    );
}
