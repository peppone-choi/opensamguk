'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Modal, StatusView, useViewportClass } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { PolicySheet } from '@/components/territory/PolicyParts';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { commanderyRows, commanderySummary, sortCommandery, type CommanderySort } from '@/lib/commandery-view';
import { availabilityOf } from '@/lib/input-availability';
import { commanderyPolicyRows } from '@/lib/territory-view';
import { CommanderyHeader, CommanderyPolicyCard, CommanderySummaryCard, CommanderyTable, type CommanderyScope } from './CommanderyParts';
import styles from './county.module.css';

export interface CommanderyScreenProps {
    /** 주소의 군 id. 「우리 세력 전체」로 시작하면(옛 `/game/my-cities` 308) initialScope NATION. */
    readonly commanderyId: string;
    readonly initialScope?: CommanderyScope;
    readonly hrefs: {
        readonly county: (cityId: number) => string;
        readonly flow: (inputId: string, target: string) => string;
    };
}

/**
 * 군 내정 현황 화면 본문(P-T03) — 머리(군 이름 · 범위 · 첩보) · 현 표(데스크톱) / 현 카드(모바일) · 오른쪽 360 군 방침 · 군 요약.
 * 현 목록(K4-11 첫 판)에 방침 · 공사 · 창고를 잇는다. 7지표 · 민심 위험 · 적 군단은 서버 보강 전까지 준비 중.
 */
export function CommanderyScreen({ commanderyId, initialScope = 'COMMANDERY', hrefs }: CommanderyScreenProps) {
    const { generalId } = useGameSession();
    const router = useRouter();
    const viewport = useViewportClass();
    // 구조가 다른 것은 모바일뿐 — 태블릿은 데스크톱 구조에 CSS 로 줄인다. 재기 전(null)은 뼈대.
    const mobile = viewport === null ? null : viewport === 'mobile';
    const [scope, setScope] = useState<CommanderyScope>(initialScope);
    const [sort, setSort] = useState<CommanderySort>('name');
    const [reload, setReload] = useState(0);
    const [sheet, setSheet] = useState(false);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
    const dir = useCampaignRead((id, s) => api.counties(id, scope, scope === 'COMMANDERY' ? commanderyId : null, s), [scope, commanderyId, reload]);
    const policies = useCampaignRead((id, s) => api.campaignPolicies(id, s), [reload]);
    const works = useCampaignRead((id, s) => api.campaignWorks(id, s), [reload]);
    const warehouses = useCampaignRead((id, s) => api.warehouses(id, s));

    if (mobile === null || (dir.loading && !dir.data)) return <StatusView kind="loading" rows={8} />;
    if (dir.error) return <StatusView kind="error" title="현 목록을 불러오지 못했습니다" errorCode={dir.error.split(':')[0]} onRetry={() => setReload((n) => n + 1)} />;
    const serverNotice = campaignReadNotice(dir, dir.data?.status);
    if (serverNotice || !dir.data) return <StatusView kind="waiting" title={serverNotice ?? '현 목록을 받지 못했습니다'} />;

    const rows = sortCommandery(commanderyRows(dir.data, policies.data, works.data, warehouses.data), sort);
    const title = scope === 'NATION' ? '우리 세력 전체' : dir.data.commandery?.name ?? '이 군';
    const cmdPolicy = policies.data ? commanderyPolicyRows(policies.data).find((r) => r.targetId === commanderyId) ?? null : null;
    const policyAvail = cmdPolicy
        ? availabilityOf('policy.set', { options: { available: cmdPolicy.settable, code: cmdPolicy.blocked?.code, reason: cmdPolicy.blocked?.reason } })
        : availabilityOf('policy.set', { options: { available: false, reason: '군 방침은 군주가 정합니다.' } });

    const submit = async (body: Readonly<Record<string, unknown>>) => {
        if (generalId == null) return;
        setBusy(true);
        try {
            const out = await api.campaignDomestic(generalId, 'policy', body);
            if (isIntakeQueued(out)) { setNotice({ tone: 'ok', text: '군 방침을 접수했습니다 — 다음 턴부터 소속 현 전체에 적용합니다.' }); setSheet(false); setReload((n) => n + 1); }
            else if (isIntakeDenied(out)) setNotice({ tone: 'error', text: out.reason?.trim() || '접수하지 못했습니다.' });
        } catch { setNotice({ tone: 'error', text: '보내지 못했습니다 — 다시 해 보세요.' }); } finally { setBusy(false); }
    };

    const head = <CommanderyHeader title={title} scope={scope} onScopeChange={setScope} scout={availabilityOf('action.scout')}
        onScout={() => router.push(hrefs.flow('action.scout', `commandery:${commanderyId}`))} />;
    const noticeLine = notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null;
    const table = <CommanderyTable rows={rows} sort={sort} onSortChange={setSort} countyHref={hrefs.county} mobile={mobile} />;
    const side = (
        <>
            {scope === 'COMMANDERY' ? <CommanderyPolicyCard row={cmdPolicy} availability={policyAvail} onChange={() => setSheet(true)} /> : null}
            <CommanderySummaryCard summary={commanderySummary(rows)} />
        </>
    );
    const modal = sheet && cmdPolicy && policies.data ? (
        <Modal ariaLabel={`${title} 군 방침`} onClose={() => setSheet(false)} overlayClassName={mobile ? styles.sheetBottom : styles.sheetRight}>
            <PolicySheet policies={policies.data} row={cmdPolicy} busy={busy} onSubmit={(b) => void submit(b)} onCancel={() => setSheet(false)} />
        </Modal>
    ) : null;

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
                {head}
                {noticeLine}
                {side}
                {table}
                {modal}
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            {head}
            {noticeLine}
            <div className={styles.columns}>
                <section className={`os-panel ${styles.colRule}`} aria-label="현 표">{table}</section>
                <section className={`os-panel ${styles.colRight}`} aria-label="군 방침 · 요약">{side}</section>
            </div>
            {modal}
        </div>
    );
}
