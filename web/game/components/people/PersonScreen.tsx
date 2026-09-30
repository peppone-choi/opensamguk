'use client';

import Link from 'next/link';
import { useState } from 'react';
import { Chip, Modal, StatusView } from '@opensamguk/ui';
import { AptitudeCells, StatCells } from '@/components/retinue/StatCells';
import { PlacementSheet } from '@/components/territory/PlacementParts';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { availabilityOf } from '@/lib/input-availability';
import { stateCells } from '@/lib/person-view';
import { retinueRows } from '@/lib/retinue-view';
import { useIsMobile } from '@/lib/use-viewport';
import { PersonActions, PersonHero, PersonStateGrid, PersonWaitingPanels } from './PersonParts';
import styles from './people.module.css';

export interface PersonScreenProps {
    readonly generalId: number;
    readonly hrefs: {
        readonly myRetinue: string;
        readonly records: (generalId: number) => string;
        readonly letter: (generalId: number) => string;
        readonly people: string;
    };
}

/**
 * 인물 상세 화면 본문(P-R03). 단건 조회(K4-13) 전에는 채울 수 있는 관계만: 나(front-info) · 내 부 인물(부 · 배치 조회).
 * 그 밖의 인물은 이름 · 값을 짐작해 채우지 않고 서버 대기 한 판 + 인물 일람 고리.
 */
export function PersonScreen({ generalId, hrefs }: PersonScreenProps) {
    const { frontInfo, generalId: me } = useGameSession();
    const mobile = useIsMobile();
    const [reload, setReload] = useState(0);
    const [placing, setPlacing] = useState(false);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
    const retinue = useCampaignRead((id, s) => api.campaignRetinue(id, s), [reload]);
    const posts = useCampaignRead((id, s) => api.campaignPosts(id, s), [reload]);

    if (mobile === null || (retinue.loading && !retinue.data)) return <StatusView kind="loading" rows={6} />;

    const self = me != null && generalId === me;
    const rows = retinue.data ? retinueRows(retinue.data, posts.data) : [];
    const mine = rows.find((r) => r.generalId === generalId) ?? null;

    if (!self && !mine) {
        return (
            <StatusView kind="waiting" title="이 인물의 카드 — 준비 중"
                body={<>내 부 밖 인물의 카드는 서버가 곧 줍니다. 지금은 <Link href={hrefs.people}>인물 일람</Link>에서 공개 칸을 볼 수 있습니다.</>} />
        );
    }

    const g = frontInfo?.general;
    const selfStats = g && g.politics != null && g.charm != null
        ? { leadership: g.leadership, strength: g.strength, intel: g.intel, politics: g.politics, charm: g.charm } : null;
    const name = self ? g?.name ?? '나' : mine!.name;
    const picture = self ? g?.picture ?? null : mine!.picture;
    const imageServer = self ? g?.imageServer ?? 0 : mine!.imageServer;
    const affiliation = frontInfo?.nation?.name ?? null;
    const relation = self ? 'SELF' as const : 'RETINUE' as const;
    const card = mine ? posts.data?.cards.find((c) => c.cardId === mine.retainerId) ?? null : null;

    const submit = async (body: Readonly<Record<string, unknown>>) => {
        if (me == null) return;
        setBusy(true);
        try {
            const out = await api.campaignDomestic(me, 'placement', body);
            if (isIntakeQueued(out)) { setNotice({ tone: 'ok', text: '배치를 접수했습니다 — 카드의 다음 턴부터 부임합니다.' }); setPlacing(false); setReload((n) => n + 1); }
            else if (isIntakeDenied(out)) setNotice({ tone: 'error', text: out.reason?.trim() || '배치를 받지 못했습니다.' });
        } catch { setNotice({ tone: 'error', text: '배치를 보내지 못했습니다 — 다시 해 보세요.' }); } finally { setBusy(false); }
    };

    const actions = (
        <PersonActions relation={relation}
            hrefs={{ myRetinue: hrefs.myRetinue, records: hrefs.records(generalId), letter: hrefs.letter(generalId) }}
            placement={mine && mine.isHuman !== true ? {
                availability: availabilityOf('placement.assign', {
                    options: mine.post.placeable == null ? null : { available: mine.post.placeable, code: mine.post.blocked?.code, reason: mine.post.blocked?.reason },
                }),
                onAct: () => setPlacing(true),
            } : undefined} />
    );
    const cells = stateCells({ relation, location: self ? frontInfo?.city?.name ?? null : null, post: mine?.post.active ?? null, loyalty: mine?.loyalty ?? null });

    return (
        <div className={mobile ? styles.peopleScreenMobile : styles.personScreen}>
            <PersonHero name={name} picture={picture} imageServer={imageServer} relation={relation} affiliation={affiliation}
                ringColor={frontInfo?.nation?.color ?? null} mobile={mobile} actions={actions} />
            <div className={styles.personGrid}>
                {notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null}
                <section className="os-panel" aria-label="능력">
                    <StatCells stats={self ? selfStats : mine!.stats} missing="?" />
                </section>
                <section className="os-panel" aria-label="역할 적성">
                    <AptitudeCells aptitudes={self ? null : mine!.aptitudes} missing="?" />
                </section>
                {mine ? (
                    <section className="os-panel" aria-label="결속">
                        <span className={styles.chips}>
                            {mine.bonds.length === 0 ? <span className={styles.muted}>결속 없음</span> : mine.bonds.map((b, i) => (
                                <Chip key={i} tone="bronze">{b.nativeCountyName ? `${b.label} · ${b.nativeCountyName}` : b.label}</Chip>
                            ))}
                        </span>
                    </section>
                ) : null}
                <section className="os-panel" aria-label="자리 · 상태"><PersonStateGrid cells={cells} /></section>
                <PersonWaitingPanels />
            </div>
            {placing && card && posts.data ? (
                <Modal ariaLabel={`${name} 배치`} onClose={() => setPlacing(false)} overlayClassName={mobile ? styles.sheetBottom : styles.sheetRight}>
                    <PlacementSheet card={card} posts={posts.data} busy={busy} onSubmit={(b) => void submit(b)} onCancel={() => setPlacing(false)} />
                </Modal>
            ) : null}
        </div>
    );
}
