'use client';

// 장수 만들기 접수 뒤 — 보드 V31K5CreatePending(만드는 중 · 거절). 역사 인물(P-E03) · 새 장수(P-E02)가 같이 쓴다.
// 접수(202)는 「만들었다」가 아니다. 결과가 CREATED 일 때만 다음 화면으로 넘어간다(부르는 쪽).

import type { ReactNode } from 'react';
import { Button, Panel, Portrait, SectionHeader, StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useOpenHelp } from '@/hooks/useOpenHelp';
import type { CreationPhase } from '@/hooks/useCreationRequest';
import styles from './creation.module.css';

export default function CreationProgress({ phase, portrait, next, onRetry, retryLabel }: {
    readonly phase: Exclude<CreationPhase, { kind: 'idle' }>;
    readonly portrait?: { readonly picture: string | null; readonly name: string } | null;
    /** 만들어지면 갈 곳 한 줄(예: 「그 인물의 자리로」). */
    readonly next: string;
    readonly onRetry: () => void;
    readonly retryLabel: string;
}) {
    const openHelp = useOpenHelp();
    let body: ReactNode;
    if (phase.kind === 'sending' || phase.kind === 'pending' || phase.kind === 'created') {
        body = (
            <Panel className={styles.progress} aria-label="장수를 만드는 중">
                <SectionHeader title="접수했습니다" sub="결과 확인" />
                <div className={styles.progressBody}>
                    {portrait ? <Portrait picture={portrait.picture} size="card" alt={`${portrait.name} 초상`} /> : null}
                    <p className={styles.progressTitle}>장수를 만드는 중입니다</p>
                    <p className={styles.progressText}>서버가 반영하면 곧바로 {next} 넘어갑니다. 이 창을 닫아도 로비나 입구에서 이어서 확인합니다.</p>
                    <StatusView kind="loading" rows={1} />
                </div>
            </Panel>
        );
    } else if (phase.kind === 'slow') {
        body = (
            <Panel className={styles.progress} aria-label="아직 반영되지 않음">
                <SectionHeader title="아직 반영되지 않았습니다" sub="접수는 됐습니다" />
                <div className={styles.progressBody}>
                    <StatusView kind="waiting" title="서버가 아직 결과를 주지 않았습니다" body="접수한 요청은 남아 있습니다. 잠시 뒤 입구에서 다시 확인하세요." />
                    <CampaignLink slug="" className="os-button os-button--ghost">입구로</CampaignLink>
                </div>
            </Panel>
        );
    } else {
        const message = phase.kind === 'rejected' ? phase.error.message : phase.message;
        const owned = phase.kind === 'rejected' && phase.error.code === 'GENERAL_ALREADY_OWNED';
        body = (
            <Panel className={styles.progress} aria-label="만들지 못했습니다">
                <SectionHeader title="만들지 못했습니다" sub={phase.kind === 'rejected' ? '서버 문장 그대로' : '연결 오류'} />
                <div className={styles.progressBody}>
                    <p className={styles.alert} role="alert">{message}</p>
                    {owned ? (
                        <div className={styles.actions}><CampaignLink slug="" className="os-button os-button--primary">작전실로</CampaignLink></div>
                    ) : (
                        <>
                            <p className={styles.progressText}>다른 인물을 고르거나 직접 만드세요.</p>
                            <div className={styles.actions}>
                                <Button variant="primary" onClick={onRetry}>{retryLabel}</Button>
                                <CampaignLink slug="create" className="os-button os-button--ghost">직접 만들기</CampaignLink>
                                <Button variant="ghost" onClick={() => openHelp('topic:concepts.createGeneral')}>도움말 — 장수 만들기</Button>
                            </div>
                        </>
                    )}
                </div>
            </Panel>
        );
    }
    return <div className={styles.progressWrap}>{body}</div>;
}
