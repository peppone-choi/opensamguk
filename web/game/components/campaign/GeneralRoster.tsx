'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Chip, Panel, Portrait } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { useHwihaRead, useHwihaRenown, type FiveStats, type PersonCard } from '@/lib/hwiha-reads';
import { hwihaHref } from '@/lib/hwiha-screens';
import { useHwihaSession } from '@/lib/hwiha-session';
import { Empty, hwihaReadNotice } from './GameStates';
import styles from './GeneralRoster.module.css';

const fmt = new Intl.NumberFormat('ko-KR');
const loyaltyTone = (loyalty: number) => (loyalty >= 80 ? 'moss' : loyalty < 50 ? 'rust' : 'info');

type SortKey = 'order' | 'cost' | 'loyalty';
const SORTS: ReadonlyArray<{ key: SortKey; label: string }> = [
    { key: 'order', label: '등록순' },
    { key: 'cost', label: '코스트순' },
    { key: 'loyalty', label: '충성순' },
];

const STAT_KEYS: ReadonlyArray<{ key: keyof FiveStats; label: string }> = [
    { key: 'leadership', label: '통' },
    { key: 'strength', label: '무' },
    { key: 'intel', label: '지' },
    { key: 'politics', label: '정' },
    { key: 'charm', label: '매' },
];

/** 능력치 다섯 칸 — 글자·수·막대. samnet 장수 카드의 미니 막대 자리. 100 을 넘으면 막대는 끝까지 찬다. */
function StatBars({ stats }: { stats: Partial<FiveStats> | null | undefined }) {
    return (
        <div className={styles.stats}>
            {STAT_KEYS.map(({ key, label }) => {
                const value = stats?.[key];
                return (
                    <div key={key} className={styles.stat}>
                        <span className={styles.statLabel}>{label}</span>
                        <span className={`os-num ${styles.statValue}`}>{value ?? '—'}</span>
                        <span className={styles.statBar} aria-hidden>
                            <i style={{ width: `${Math.min(100, Math.max(0, value ?? 0))}%` }} />
                        </span>
                    </div>
                );
            })}
        </div>
    );
}

/**
 * 장수 — 나와 내 휘하. 작전실 오른쪽 열의 맨 위, samnet 의 장수 카드 열과 같은 자리·같은 모양이다.
 * 나는 `front-info`, 휘하 인물은 `GET /api/retinue`. 휘하 카드를 누르면 휘하 편성 상세로 간다.
 */
export default function GeneralRoster() {
    const { frontInfo, serverId, isHwihaWorld, generalId } = useHwihaSession();
    const renown = useHwihaRenown();
    const retinue = useHwihaRead((id, signal) => api.hwihaRetinue(id, signal));
    const [sort, setSort] = useState<SortKey>('order');
    const [turnTime, setTurnTime] = useState<string | null>(null);

    useEffect(() => {
        if (generalId == null) return;
        let alive = true;
        api.reservedCommands(generalId)
            .then((res) => alive && setTurnTime(res.turnTime ?? null))
            .catch(() => undefined);
        return () => {
            alive = false;
        };
    }, [generalId]);

    const people = useMemo(() => {
        const list = [...(retinue.data?.people ?? [])];
        if (sort === 'cost') list.sort((a, b) => (b.cost ?? -1) - (a.cost ?? -1) || a.retainerId - b.retainerId);
        if (sort === 'loyalty') list.sort((a, b) => a.loyalty - b.loyalty || a.retainerId - b.retainerId);
        return list;
    }, [retinue.data?.people, sort]);

    const me = frontInfo?.general;
    if (!frontInfo || !me?.hasGeneral) return null;
    const units = retinue.data?.units ?? [];
    const notice = isHwihaWorld ? hwihaReadNotice(retinue, retinue.data?.status) : null;
    const troopsOf = (p: PersonCard) => {
        const led = units.filter((u) => u.commanderRetainerId === p.retainerId);
        if (led.length === 0) return null;
        return `${led[0].crewTypeName} ${fmt.format(led.reduce((sum, u) => sum + u.troops, 0))}`;
    };

    return (
        <Panel className={styles.panel}>
            <div className={styles.head}>
                <div className={styles.sorts} role="tablist" aria-label="장수 정렬">
                    {SORTS.map((s) => (
                        <button
                            key={s.key}
                            type="button"
                            role="tab"
                            aria-selected={sort === s.key}
                            className={`${styles.sort}${sort === s.key ? ` ${styles.sortOn}` : ''}`}
                            onClick={() => setSort(s.key)}
                        >
                            {s.label}
                        </button>
                    ))}
                </div>
                {retinue.data ? (
                    <Chip tone={retinue.data.overCapacity ? 'rust' : 'bronze'}>
                        {`⚖ ${retinue.data.costSum ?? '—'} / ${retinue.data.renown ?? '—'}`}
                    </Chip>
                ) : null}
            </div>

            <div className={`${styles.card} ${styles.cardSelf}`}>
                <Portrait picture={me.picture ?? null} imageServer={me.imageServer ?? 0} size="card-44" alt={`${me.name ?? ''} 초상`} />
                <div className={styles.body}>
                    <div className={styles.top}>
                        <span className={styles.name}>{me.name}</span>
                        <Chip tone="info">나</Chip>
                        <span className={styles.meta}>{frontInfo.city?.name ?? ''}</span>
                        <span className={styles.spacer} />
                        {turnTime ? <span className={`os-num ${styles.turn}`}>{`턴 ${turnTime}`}</span> : null}
                    </div>
                    <StatBars stats={me} />
                    <div className={styles.foot}>
                        <span className="os-num">{`병력 ${fmt.format(me.crew)}`}</span>
                        {isHwihaWorld ? <span className={styles.renown}>{`명망 ${renown ?? '—'}`}</span> : null}
                    </div>
                </div>
            </div>

            {notice ? <Empty>{notice}</Empty> : null}
            {!notice && isHwihaWorld && people.length === 0 ? <Empty>거느린 인물이 없습니다.</Empty> : null}
            {people.map((p) => {
                const troops = troopsOf(p);
                return (
                    <Link key={p.retainerId} href={`${hwihaHref('retinue', serverId)}?person=${p.retainerId}`} className={styles.card}>
                        <Portrait picture={p.picture} imageServer={p.imageServer} size="card-44" alt={`${p.name} 초상`} />
                        <div className={styles.body}>
                            <div className={styles.top}>
                                <span className={styles.name}>{p.name}</span>
                                {p.roleLabel ? <span className={styles.meta}>{p.roleLabel}</span> : <Chip tone="rust">미배치</Chip>}
                                {p.departureOrder != null ? <Chip tone="rust">이탈 위험</Chip> : null}
                                <span className={styles.spacer} />
                                <Chip tone={loyaltyTone(p.loyalty)}>{`충성 ${p.loyalty}`}</Chip>
                            </div>
                            <StatBars stats={p.stats} />
                            <div className={styles.foot}>
                                <span className="os-num">{troops ?? '병력 없음'}</span>
                                <span>{p.taskLabel ? `임무 ${p.taskLabel}` : '임무 없음'}</span>
                                {p.bonds.map((b) => (
                                    <span key={b.kind} className={styles.bond}>{b.label}</span>
                                ))}
                                <span className={styles.spacer} />
                                <span className={`os-num ${styles.cost}`}>{`코스트 ${p.cost ?? '—'}`}</span>
                            </div>
                        </div>
                    </Link>
                );
            })}
        </Panel>
    );
}
