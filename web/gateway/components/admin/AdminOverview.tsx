'use client';

import { useEffect, useState } from 'react';
import { Chip, Panel, SectionHeader } from '@opensamguk/ui';
import StateLine from '@/components/status/StateLine';
import { GAME_URL } from '@/lib/constants';
import { resolveServerGamePath } from '@/lib/serverGameUrl';

// 운영 콘솔 「개요」 — 버전(admin/version) · 배포 상태(admin/deploy/status) · 데몬 상태(admin/turn-daemon/status) 한눈에.
// 위험 등급: 조회. 값은 API 에서만 오고, 못 받으면 「조회 실패」로 남긴다.
interface ServiceVersion { reachable: boolean; version: string | null; imageTag: string | null; buildTime: string | null }
interface ServerVersion { id: string; name: string; generation?: number | null; scenarioCode?: string | null; gameApi: ServiceVersion; gameEngine: ServiceVersion; skew: boolean }
export interface VersionResponse { gateway: ServiceVersion; servers: ServerVersion[]; skew: boolean }
interface DeployStatus { configured: boolean; serverId: string | null; currentTag: string | null; latestTag?: string | null; promotionAvailable?: boolean; message?: string | null }
interface DaemonStatus { paused?: boolean; locked?: boolean; running?: boolean; catchUp?: { active?: boolean; multiplier?: number } | null; [key: string]: unknown }
interface ScenarioList { scenarios: { code: string; title: string }[] }

async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(`/api/proxy/${path}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
    return (await res.json()) as T;
}

function versionText(v: ServiceVersion): string {
    if (!v.reachable) return '연결 실패';
    return [v.imageTag, v.version].filter(Boolean).join(' · ') || '버전 정보 없음';
}

/** 턴 칸(설계서 §3.4 O22–O23) — 옛 「동결중 · 가동중」(PHP _119) 대신 쉬운 말. */
function TurnChip({ daemon }: { readonly daemon: DaemonStatus | null | undefined }) {
    if (daemon === undefined) return <span className="gw31-card__line--muted">확인하는 중</span>;
    if (daemon === null) return <Chip tone="rust">조회 실패</Chip>;
    if (daemon.paused === true || daemon.locked === true) return <Chip tone="rust">턴 멈춤</Chip>;
    if (daemon.catchUp?.active) return <Chip tone="bronze">따라잡는 중 · {daemon.catchUp.multiplier ?? 2}배속</Chip>;
    if (daemon.running === false) return <Chip tone="rust">턴 멈춤</Chip>;
    return <Chip tone="moss">턴 도는 중</Chip>;
}

export default function AdminOverview({ onNavigate, onVersion }: {
    readonly onNavigate?: (section: string) => void;
    /** 읽은 서버 목록을 콘솔이 턴 · 따라잡기 · 서버 탭과 함께 쓴다. */
    readonly onVersion?: (version: VersionResponse) => void;
}) {
    const [version, setVersion] = useState<VersionResponse | null | undefined>(undefined);
    const [deploy, setDeploy] = useState<Record<string, DeployStatus | null>>({});
    const [daemon, setDaemon] = useState<Record<string, DaemonStatus | null>>({});
    const [titles, setTitles] = useState<Record<string, string>>({});
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
        let alive = true;
        setVersion(undefined);
        (async () => {
            try {
                const [ver, scenarioList] = await Promise.all([
                    getJson<VersionResponse>('admin/version'),
                    getJson<ScenarioList>('admin/scenarios').catch(() => null),
                ]);
                if (!alive) return;
                setVersion(ver);
                onVersion?.(ver);
                setTitles(Object.fromEntries((scenarioList?.scenarios ?? []).map((sc) => [sc.code, sc.title])));
                const pairs = await Promise.all(
                    ver.servers.map(async (s) => {
                        const [d, t] = await Promise.all([
                            getJson<DeployStatus>(`admin/deploy/status?serverId=${encodeURIComponent(s.id)}`).catch(() => null),
                            getJson<DaemonStatus>(`admin/turn-daemon/status?serverId=${encodeURIComponent(s.id)}`).catch(() => null),
                        ]);
                        return [s.id, d, t] as const;
                    }),
                );
                if (!alive) return;
                setDeploy(Object.fromEntries(pairs.map(([id, d]) => [id, d])));
                setDaemon(Object.fromEntries(pairs.map(([id, , t]) => [id, t])));
            } catch {
                if (alive) setVersion(null);
            }
        })();
        return () => {
            alive = false;
        };
    }, [attempt, onVersion]);

    const retry = () => setAttempt((n) => n + 1);
    return (
        <div className="admin31-stack">
            <Panel className="admin31-panel" aria-label="게이트웨이">
                <SectionHeader as="h2" title="게이트웨이" />
                <div className="admin31-body">
                    {version === undefined && <StateLine kind="loading" title="버전을 확인하는 중" />}
                    {version === null && <StateLine kind="error" title="버전 정보를 불러오지 못했습니다" onRetry={retry} />}
                    {version && (
                        <div className="admin31-row">
                            <span className="os-num gw31-card__line--muted">gateway</span>
                            <span className="os-num">{versionText(version.gateway)}</span>
                            {version.skew && <Chip tone="rust">버전 불일치</Chip>}
                        </div>
                    )}
                </div>
            </Panel>
            <Panel className="admin31-panel" aria-label="게임 서버">
                <SectionHeader
                    as="h2"
                    title="게임 서버"
                    sub={version ? `${version.servers.length}대` : undefined}
                    actions={onNavigate && <button type="button" className="os-button os-button--ghost os-button--sm" onClick={() => onNavigate('server')}>서버 탭으로</button>}
                />
                <div className="admin31-body">
                    {version && version.servers.length === 0 && <StateLine kind="empty" title="등록된 게임 서버가 없습니다." />}
                    {version && version.servers.length > 0 && (
                        <table className="admin31-table">
                            <thead>
                                <tr><th>서버</th><th>시나리오</th><th>game-api</th><th>game-engine</th><th>배포 태그</th><th>턴</th><th><span className="sr-only">조치</span></th></tr>
                            </thead>
                            <tbody>
                                {version.servers.map((s) => {
                                    const d = deploy[s.id];
                                    const title = s.scenarioCode ? titles[s.scenarioCode] : undefined;
                                    return (
                                        <tr key={s.id}>
                                            <td data-label="서버">
                                                <span className="admin31-row">
                                                    <b>{s.name}</b>
                                                    {s.generation != null && <Chip tone="bronze">{s.generation}기</Chip>}
                                                    {s.skew && <Chip tone="rust">불일치</Chip>}
                                                </span>
                                            </td>
                                            <td data-label="시나리오">
                                                {title ?? (s.scenarioCode ? '' : '-')}
                                                {s.scenarioCode && <small className="os-num admin31-code">{s.scenarioCode}</small>}
                                            </td>
                                            <td data-label="game-api" className="os-num">{versionText(s.gameApi)}</td>
                                            <td data-label="game-engine" className="os-num">{versionText(s.gameEngine)}</td>
                                            <td data-label="배포 태그" className="os-num">
                                                {d === undefined ? '확인하는 중' : d ? (d.currentTag ?? '-') : '조회 실패'}
                                                {d?.promotionAvailable && d.latestTag && <Chip tone="info">새 버전 {d.latestTag}</Chip>}
                                            </td>
                                            <td data-label="턴"><TurnChip daemon={daemon[s.id]} /></td>
                                            <td>
                                                <a className="os-button os-button--ghost os-button--sm" href={resolveServerGamePath(undefined, s.id, GAME_URL, 'admin')}>게임 관리</a>
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    )}
                    <p className="gw31-card__line gw31-card__line--muted">마지막 턴 시각 · 턴 멈춤 의심은 턴 시각 응답(K10-01)이 오면 보인다 — 서버 대기.</p>
                </div>
            </Panel>
        </div>
    );
}
