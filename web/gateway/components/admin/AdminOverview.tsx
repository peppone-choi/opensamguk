'use client';

import { useEffect, useState } from 'react';
import { Chip, SectionHeader } from '@opensamguk/ui';

// 운영 콘솔 「개요」 — 버전(admin/version) · 배포 상태(admin/deploy/status) · 데몬 상태(admin/turn-daemon/status) 한눈에.
// 위험 등급: 조회. 값은 API 에서만 오고, 못 받으면 「조회 실패」로 남긴다.
interface ServiceVersion { reachable: boolean; version: string | null; imageTag: string | null; buildTime: string | null }
interface ServerVersion { id: string; name: string; generation?: number | null; scenarioCode?: string | null; gameApi: ServiceVersion; gameEngine: ServiceVersion; skew: boolean }
interface VersionResponse { gateway: ServiceVersion; servers: ServerVersion[]; skew: boolean }
interface DeployStatus { configured: boolean; serverId: string | null; currentTag: string | null; latestTag?: string | null; promotionAvailable?: boolean; message?: string | null }
interface DaemonStatus { paused?: boolean; locked?: boolean; running?: boolean; [key: string]: unknown }

async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(`/api/proxy/${path}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
    return (await res.json()) as T;
}

function versionText(v: ServiceVersion): string {
    if (!v.reachable) return '연결 실패';
    return [v.imageTag, v.version].filter(Boolean).join(' · ') || '버전 정보 없음';
}

function daemonText(d: DaemonStatus | null | undefined): string {
    if (!d) return '조회 실패';
    if (d.paused === true || d.locked === true) return '동결중';
    if (d.running === false) return '정지';
    return '가동중';
}

export default function AdminOverview({ onNavigate }: { readonly onNavigate?: (section: string) => void }) {
    const [version, setVersion] = useState<VersionResponse | null | undefined>(undefined);
    const [deploy, setDeploy] = useState<Record<string, DeployStatus | null>>({});
    const [daemon, setDaemon] = useState<Record<string, DaemonStatus | null>>({});

    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                const ver = await getJson<VersionResponse>('admin/version');
                if (!alive) return;
                setVersion(ver);
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
    }, []);

    return (
        <div className="admin-overview">
            <section className="os-panel os-panel--static" aria-label="게이트웨이">
                <SectionHeader title="게이트웨이" sub="위험 등급: 조회" />
                <div className="admin-overview__kv">
                    {version === undefined && <span className="text-muted">불러오는 중…</span>}
                    {version === null && <span className="text-muted" role="alert">버전 정보를 불러오지 못했습니다.</span>}
                    {version && (
                        <>
                            <span>gateway</span>
                            <span className="os-num">{versionText(version.gateway)}</span>
                            {version.skew && <Chip tone="rust">버전 불일치</Chip>}
                        </>
                    )}
                </div>
            </section>
            <section className="os-panel os-panel--static" aria-label="게임 서버">
                <SectionHeader title="게임 서버" sub={version ? `${version.servers.length}대` : undefined} actions={onNavigate && <button type="button" className="os-button os-button--ghost os-button--sm" onClick={() => onNavigate('server')}>서버 제어로</button>} />
                {version && version.servers.length === 0 && <p className="text-muted admin-overview__empty">등록된 게임 서버가 없습니다.</p>}
                {version && version.servers.length > 0 && (
                    <div className="game-table-wrap">
                        <table className="game-table os-table">
                            <thead>
                                <tr>
                                    <th>서버</th>
                                    <th>시나리오</th>
                                    <th>game-api</th>
                                    <th>game-engine</th>
                                    <th>배포 태그</th>
                                    <th>데몬</th>
                                </tr>
                            </thead>
                            <tbody>
                                {version.servers.map((s) => (
                                    <tr key={s.id}>
                                        <td>
                                            {s.name}
                                            {s.generation != null && <Chip tone="bronze">{s.generation}기</Chip>}
                                            {s.skew && <Chip tone="rust">불일치</Chip>}
                                        </td>
                                        <td>{s.scenarioCode ?? '-'}</td>
                                        <td className="os-num">{versionText(s.gameApi)}</td>
                                        <td className="os-num">{versionText(s.gameEngine)}</td>
                                        <td className="os-num">{deploy[s.id] === undefined ? '…' : deploy[s.id] ? (deploy[s.id]?.currentTag ?? '-') : '조회 실패'}</td>
                                        <td>{daemon[s.id] === undefined ? '…' : daemonText(daemon[s.id])}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
        </div>
    );
}
