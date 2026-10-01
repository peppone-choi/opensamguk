'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useMemo, useState } from 'react';
import { Chip, Seg, StatusView, useProvinceName, useViewportClass } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { useOpenHelp } from '@/hooks/useOpenHelp';
import { api } from '@/lib/api';
import { useCampaignRead, type Siege } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { availabilityOf } from '@/lib/input-availability';
import { useRecordNames } from '@/lib/records-names';
import { fortRow, siegeProgressText, siegeStatus, siegeVerdict, type FortRow } from '@/lib/siege-view';
import {
    FallNote, FortState, Section, SiegeCommands, SiegeList, SiegeState, SiegeTimeline, StratagemLink, SurrenderNote, countyName, type SiegePick,
} from './SiegeParts';
import styles from './siege.module.css';

export interface SiegeScreenProps {
    readonly hrefs: {
        /** 명령 흐름(작전실 `?do=<inputId>`) — 순은 흐름에서 고른다(설계 P-C02, K6 Q9). */
        readonly flow: (inputId: string) => string;
        readonly stratagem: string;
    };
    /** 구역 한글 이름 — 넘기지 않으면 공용 useProvinceName(지도 캐시). 못 풀면 「이름 모를 구역」. */
    readonly provinceName?: (id: string) => string | null;
    /** 주소 `?county=` — 처음 고를 포위(옛 `/game/siege?county=` 308 도 이리 온다). 없는 현이면 무시한다. */
    readonly initialCounty?: number | null;
}

type Pane = 'state' | 'log';

/**
 * 공성 본문(P-C02) — 데스크톱: 포위 목록 360 / 고른 포위 형편 · 기록 / 명령 · 항복 권고 · 함락되면 · 계책 360.
 * 모바일: 목록 카드 → 누르면 상세(「형편 · 기록」) + 아래 단추 줄(강공 · 항복 권고).
 * 입력은 바로 보내지 않고 명령 흐름을 연다 — 순을 고르는 것은 흐름의 몫이다(옛 화면은 첫 빈 순에 자동 예약했다).
 */
export function SiegeScreen({ hrefs, provinceName, initialCounty = null }: SiegeScreenProps) {
    const viewport = useViewportClass();
    const mobile = viewport === null ? null : viewport === 'mobile';
    const { generalId, frontInfo } = useGameSession();
    const router = useRouter();
    const openHelp = useOpenHelp();
    const [reload, setReload] = useState(0);
    const sieges = useCampaignRead((id, s) => api.campaignSieges(id, s), [reload]);
    const roads = useCampaignRead((id, s) => api.roadForts(id, s), [reload]);
    const names = useRecordNames(generalId, frontInfo?.general.name ?? null);
    const cachedProvince = useProvinceName();
    const [picked, setPicked] = useState<SiegePick | null>(initialCounty != null ? { kind: 'siege', countyId: initialCounty } : null);
    const [pane, setPane] = useState<Pane>('state');

    const myNationId = frontInfo?.nation?.id ?? null;
    const forts: readonly FortRow[] = useMemo(() => (roads.data?.status === 'READY' ? roads.data.forts : []).map((f) => fortRow(f, {
        province: provinceName ?? ((id) => cachedProvince(id) ?? null),
        nation: names.nation,
        myNationId,
    })), [roads.data, provinceName, cachedProvince, names.nation, myNationId]);
    const rows: readonly Siege[] = sieges.data?.status === 'READY' ? sieges.data.sieges : [];

    if (mobile === null) return <StatusView kind="loading" rows={4} />;
    if (sieges.error) {
        return <StatusView kind="error" title="포위를 불러오지 못했습니다" errorCode={sieges.errorCode ?? undefined} onRetry={() => setReload((n) => n + 1)} />;
    }
    const notice = campaignReadNotice(sieges, sieges.data?.status);
    if (notice) return <StatusView kind="waiting" title={notice} />;
    if (!sieges.data) return <StatusView kind="loading" rows={4} />;
    if (rows.length === 0 && forts.length === 0 && !roads.loading) {
        return (
            <StatusView kind="empty" title="포위 중인 성이 없습니다" body="군단이 적 성에 닿으면 여기에 나옵니다."
                actions={<Link href={hrefs.flow('action.deploy')} className="os-button os-button--primary">출병 — 명령 목록에 넣기</Link>} />
        );
    }

    // 데스크톱은 늘 하나를 보인다(고른 것 · 없으면 진행 중인 첫 포위). 모바일은 목록에서 고른 뒤에만 상세(하나뿐이면 바로).
    const only: SiegePick | null = rows.length + forts.length === 1
        ? (rows[0] ? { kind: 'siege', countyId: rows[0].countyId } : { kind: 'fort', id: forts[0].id }) : null;
    const first: SiegePick | null = (rows.find((s) => s.status === 'ACTIVE') ?? rows[0])
        ? { kind: 'siege', countyId: (rows.find((s) => s.status === 'ACTIVE') ?? rows[0]).countyId }
        : forts[0] ? { kind: 'fort', id: forts[0].id } : null;
    const current = (picked && (picked.kind === 'siege' ? rows.some((s) => s.countyId === picked.countyId) : forts.some((f) => f.id === picked.id)) ? picked : null)
        ?? (mobile ? only : first);
    const siege = current?.kind === 'siege' ? rows.find((s) => s.countyId === current.countyId) ?? null : null;
    const fort = current?.kind === 'fort' ? forts.find((f) => f.id === current.id) ?? null : null;

    const go = (inputId: string) => router.push(hrefs.flow(inputId));
    const assault = siege ? availabilityOf('action.assault', { options: siegeVerdict(siege, 'action.assault') }) : null;
    const demand = siege ? availabilityOf('action.demandSurrender', { options: siegeVerdict(siege, 'action.demandSurrender') }) : null;
    const besiege = fort && !fort.mine ? availabilityOf('action.siegeRoadFort', { options: { available: fort.canBesiege } }) : null;
    const active = rows.filter((s) => s.status === 'ACTIVE').length;
    const roadsLine = roads.error ? <p className={styles.errLine} role="status">도로 보루를 불러오지 못했습니다.</p> : null;
    const list = <SiegeList sieges={rows} forts={forts} picked={current} onPick={(p) => { setPicked(p); setPane('state'); }} />;
    const fortButton = besiege ? <HelpedInputAction inputId="action.siegeRoadFort" availability={besiege} label="보루 포위 — 순 고르기" block onAct={() => go('action.siegeRoadFort')} /> : null;

    if (mobile) {
        if (!siege && !fort) {
            return (
                <div className={styles.screenMobile}>
                    <p className={styles.muted} role="status">{`포위 ${active}곳 · 보루 ${forts.length}`}</p>
                    {roadsLine}
                    {list}
                    <p className={styles.note}>군단이 적 성에 닿으면 여기에 나옵니다.</p>
                </div>
            );
        }
        const status = siege ? siegeStatus(siege.status) : null;
        return (
            <div className={styles.screenMobile}>
                <div className={styles.mHead}>
                    {only ? null : <button type="button" className="os-button os-button--sm" onClick={() => setPicked(null)}>← 포위 목록</button>}
                    <h3 className={`os-serif ${styles.mTitle}`}>{siege ? countyName(siege) : fort!.name}</h3>
                    {status ? <Chip tone={status.tone}>{status.label}</Chip> : <Chip>{fort!.mine ? '우리 보루' : '보루'}</Chip>}
                </div>
                {siege ? (
                    <>
                        <p className={styles.muted}>{`${siege.besieger.nationName ?? '포위 세력'} → ${siege.defenderNationName ?? '수비 세력'} · ${siegeProgressText(siege)}`}</p>
                        <Seg label="보기" value={pane} onChange={setPane} options={[{ value: 'state', label: '형편' }, { value: 'log', label: '기록' }]} />
                        {pane === 'state' ? (
                            <>
                                <SiegeState siege={siege} />
                                {siege.status === 'ACTIVE' ? <SurrenderNote siege={siege} /> : null}
                            </>
                        ) : <SiegeTimeline siege={siege} />}
                        {siege.status === 'ACTIVE' ? (
                            <div className={styles.footBar}>
                                <HelpedInputAction inputId="action.assault" availability={assault} label="강공" variant="danger" onAct={() => go('action.assault')} />
                                <HelpedInputAction inputId="action.demandSurrender" availability={demand} label="항복 권고" onAct={() => go('action.demandSurrender')} />
                            </div>
                        ) : null}
                    </>
                ) : (
                    <>
                        <FortState fort={fort!} />
                        {fortButton ? <div className={styles.footBar}>{fortButton}</div> : null}
                    </>
                )}
            </div>
        );
    }

    return (
        <div className={styles.screen}>
            <div className={styles.columns}>
                <section className={`os-panel ${styles.colList}`} aria-label="포위 목록">
                    <div className={styles.listHead}>
                        <h3 className={styles.listTitle}>포위 중인 성</h3>
                        <span className={styles.muted}>{`${active}곳 · 보루 ${forts.length}`}</span>
                    </div>
                    {roadsLine}
                    {list}
                    <p className={styles.note}>군단이 적 성에 닿으면 여기에 나옵니다. 이름을 누르면 가운데에 형편이 보입니다.</p>
                </section>
                {siege ? (
                    <>
                        <div className={styles.colMid}>
                            <Section title={countyName(siege)} sub={`${siege.besieger.nationName ?? '포위 세력'} → ${siege.defenderNationName ?? '수비 세력'} · ${siegeProgressText(siege)}`} label="형편">
                                <SiegeState siege={siege} />
                            </Section>
                            <Section title="포위 기록" sub={siege.besieger.name ? `지휘 ${siege.besieger.name}` : undefined}>
                                <SiegeTimeline siege={siege} />
                            </Section>
                        </div>
                        <div className={styles.colRight}>
                            {siege.status === 'ACTIVE' ? (
                                <>
                                    <Section title="명령" sub="명령 목록 12순에 넣는다 — 순을 고른다">
                                        <SiegeCommands assault={assault} demand={demand} onAct={go} onHelp={() => openHelp({ kind: 'input', inputId: 'action.assault' })} />
                                    </Section>
                                    <Section title="항복 권고" sub="성 안 사기 · 민심으로 판정"><SurrenderNote siege={siege} /></Section>
                                    <Section title="함락되면"><FallNote siege={siege} /></Section>
                                </>
                            ) : <Section title="끝난 포위"><p className={styles.note}>{`${siegeStatus(siege.status).label} — 명령을 넣을 수 없습니다.`}</p></Section>}
                            <Section title="계책"><StratagemLink href={hrefs.stratagem} /></Section>
                        </div>
                    </>
                ) : fort ? (
                    <>
                        <div className={styles.colMid}>
                            <Section title={fort.name} sub={`도로 보루 · ${fort.ownerName}`} label="형편"><FortState fort={fort} /></Section>
                        </div>
                        <div className={styles.colRight}>
                            {fortButton ? <Section title="명령" sub="명령 목록 12순에 넣는다 — 보루와 순을 고른다"><div className={styles.commands}>{fortButton}</div></Section> : null}
                        </div>
                    </>
                ) : null}
            </div>
        </div>
    );
}
