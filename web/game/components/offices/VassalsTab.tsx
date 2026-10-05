'use client';

// 관직 · 봉신 › 봉신(P-K04) — 보드 V31K8Vassals(목록 · 계약 상세 · 상납 이력) · MVassalSide.
// 저장된 계약 조건만 그린다(C5 #1373 PARTIAL, K8 소비 답). 서버가 아직 판정하지 않는 칸은 서버 대기(K8-04)로 둔다:
//   지금 유효한지(activityStatus) · 맺은 때 · 끝난 때(calendarStatus) · 원군 응답 기한(reinforcementResponse) ·
//   사람 여부(isHumanStatus UNAVAILABLE) · 봉신 세우기 후보(foundingOptionsStatus).
// 받은 봉신 제안(K8-02) · 받은 원군 요청(K8-17)은 아직 읽기가 없다. 계약 변경 · 끝내기 · 세우기 입력은 원장 행이 없어 그리지 않는다.
import { useState, type ReactNode } from 'react';
import { Chip, KV, Panel, SectionHeader, StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useGameSession } from '@/lib/campaign-session';
import type { VassalContract } from '@/lib/api/court-vassals';
import {
    AUTONOMY_LABEL,
    DIPLOMACY_LABEL,
    TRIBUTE_RESOURCES,
    fiefNames,
    monthlyTributeChip,
    receiptStatus,
    vassalName,
} from '@/lib/court-vassals-view';
import { useCourtVassals } from '@/lib/use-court-vassals';
import styles from './offices.module.css';

type CountyName = (countyId: number) => string | null;

export default function VassalsTab() {
    const { generalId } = useGameSession();
    const read = useCourtVassals(generalId);
    const [picked, setPicked] = useState<string | null>(null);
    const view = read.state === 'ready' ? read.view : null;
    const all = view?.kind === 'contracts' ? [...view.current, ...view.ended] : [];
    const selected = all.find((c) => c.contractId === picked) ?? (view?.kind === 'contracts' ? view.current[0] ?? view.ended[0] : undefined);

    return (
        <div className={`${styles.split} ${styles.splitVassals}`}>
            <div className={styles.col}>
                <Panel className={styles.box} aria-label="봉신 계약">
                    <SectionHeader
                        title="봉신 계약"
                        sub={view?.kind === 'contracts' ? `${view.current.length}명 · 같은 세력의 봉신 주공` : '같은 세력의 봉신 주공'}
                    />
                    {read.state === 'loading' ? <StatusView kind="loading" rows={3} /> : null}
                    {read.state === 'error' ? (
                        <StatusView kind="error" title="봉신 계약을 지금 읽을 수 없습니다" body="잠시 뒤 다시 해 보세요." onRetry={read.retry} />
                    ) : null}
                    {view?.kind === 'not-seeded' ? (
                        <Wait row="K8-04" title="봉신 계약 정보가 아직 준비되지 않았습니다" body="서버가 아직 계약 정보를 만들지 않았습니다. 계약이 없다는 뜻은 아닙니다." />
                    ) : null}
                    {view?.kind === 'unavailable' ? (
                        <StatusView kind="unavailable" title="계약 정보를 셈하지 못했습니다" body="봉신 계약을 서버가 지금 셈하지 못했습니다. 계약이 없다는 뜻은 아닙니다." onReload={read.retry} />
                    ) : null}
                    {view?.kind === 'empty' ? <StatusView kind="empty" title="봉신 계약이 없습니다" body="같은 세력의 봉신 계약이 아직 하나도 없습니다." /> : null}
                    {view?.kind === 'contracts' ? (
                        <>
                            <ContractList contracts={view.current} selected={selected} onPick={setPicked} countyName={read.countyName} />
                            {view.ended.length > 0 ? (
                                <div className={styles.endedGroup} aria-label="끝난 계약">
                                    <h4 className={styles.groupHead}>끝난 계약</h4>
                                    <ContractList contracts={view.ended} selected={selected} onPick={setPicked} countyName={read.countyName} ended />
                                </div>
                            ) : null}
                        </>
                    ) : null}
                    <Wait row="K8-04" title="봉신 세우기 후보는 아직 없습니다" body="누구를 어느 봉토로 세울 수 있는지는 서버가 아직 주지 않습니다." />
                </Panel>
                {selected ? <ContractDetail contract={selected} countyName={read.countyName} /> : null}
            </div>
            <div className={styles.col}>
                <Panel className={styles.box} aria-label="받은 봉신 제안">
                    <SectionHeader title="받은 봉신 제안" sub="동의해야 맺어집니다" />
                    <Wait row="K8-02" title="아직 없습니다" body="받은 봉신 제안은 서버가 아직 주지 않습니다." />
                </Panel>
                <Panel className={styles.box} aria-label="받은 원군 요청">
                    <SectionHeader title="받은 원군 요청" sub="봉신의 의무" />
                    <Wait row="K8-17" title="아직 없습니다" body="받은 원군 요청과 그 응답(수락 · 지연 · 줄여 보냄 · 거절)은 서버가 아직 주지 않습니다." />
                </Panel>
            </div>
        </div>
    );
}

function ContractList({ contracts, selected, onPick, countyName, ended = false }: {
    readonly contracts: readonly VassalContract[];
    readonly selected: VassalContract | undefined;
    readonly onPick: (contractId: string) => void;
    readonly countyName: CountyName;
    readonly ended?: boolean;
}) {
    return (
        <ul className={styles.contractList}>
            {contracts.map((c) => {
                const chip = ended ? null : monthlyTributeChip(c.monthlyTribute.status);
                return (
                    <li key={c.contractId}>
                        <button
                            type="button"
                            className={`${styles.contractRow} ${ended ? styles.contractEnded : ''}`.trim()}
                            aria-pressed={selected?.contractId === c.contractId}
                            onClick={() => onPick(c.contractId)}
                        >
                            <span className={styles.contractMain}>
                                <span className={styles.contractName}>
                                    {vassalName(c)}
                                    <HumanMark contract={c} />
                                </span>
                                <span className={styles.contractSub}>봉토 {fiefNames(c, countyName).join(' · ') || '없음'} · 상납 {c.tributePercent}%</span>
                            </span>
                            <span className={styles.contractSide}>
                                {ended ? <Chip>끝남</Chip> : null}
                                {chip ? <Chip tone={chip.tone}>{chip.label}</Chip> : null}
                                {!ended && !chip ? <span className={styles.waitText} data-server-wait="K8-04">이번 달 상납 준비 중</span> : null}
                                <span className={styles.contractLoyalty}>충성 {c.loyalty}</span>
                            </span>
                        </button>
                    </li>
                );
            })}
        </ul>
    );
}

/** 사람 여부 — READY 일 때만 「사람」 칩. 모르면 NPC 로 쓰지 않고 서버 대기. */
function HumanMark({ contract }: { readonly contract: VassalContract }) {
    if (contract.isHumanStatus !== 'READY') return <span className={styles.waitText} data-server-wait="K8-04">사람 여부 준비 중</span>;
    return contract.isHuman ? <Chip tone="info">사람</Chip> : null;
}

function ContractDetail({ contract: c, countyName }: { readonly contract: VassalContract; readonly countyName: CountyName }) {
    const fiefs = fiefNames(c, countyName);
    return (
        <Panel className={styles.box} aria-label={`${vassalName(c)} — 봉신 계약`}>
            <SectionHeader title={`${vassalName(c)} — 봉신 계약`} sub={c.endedTurn !== null ? '끝난 계약' : '저장된 계약 조건'} />
            <div className={styles.detail}>
                <p className={styles.fiefs}>봉토 현 {fiefs.length}곳{fiefs.length > 0 ? ` — ${fiefs.join(' · ')}` : ''}</p>
                <KV
                    items={[
                        { k: '상납률', v: `${c.tributePercent}% — 봉토 수입에서` },
                        { k: '원군 의무', v: <>{c.reinforcementTroops}명 · 응답 <WaitText>기한 준비 중</WaitText></> },
                        { k: '외교권', v: DIPLOMACY_LABEL[c.diplomacyRight] },
                        { k: '충성', v: String(c.loyalty) },
                        { k: '맺은 때', v: <WaitText>준비 중</WaitText> },
                        { k: '지금 유효한지', v: <WaitText>준비 중</WaitText> },
                    ]}
                />
                <div className={styles.autonomy} aria-label="자치">
                    <span className={styles.autonomyHead}>자치</span>
                    {c.autonomy.length === 0 ? <span className={styles.autonomyNone}>없음</span> : c.autonomy.map((a) => <Chip key={a} tone="moss">{AUTONOMY_LABEL[a]}</Chip>)}
                </div>
            </div>
            <TributeHistory contract={c} />
            <div className={styles.detailFoot}>
                <CampaignLink slug="territory/supply" className="os-button os-button--ghost">창고망 보기</CampaignLink>
            </div>
        </Panel>
    );
}

function TributeHistory({ contract: c }: { readonly contract: VassalContract }) {
    if (c.tributeHistory.length === 0) return <p className={styles.historyNone}>상납 이력이 아직 없습니다.</p>;
    return (
        <div className={styles.history}>
            <p className={styles.historyHead}>상납 이력 — 낸 양(미납이 있으면 낸 양 / 낼 양)</p>
            <div className="game-table-wrap">
                <table className="game-table os-table">
                    <thead>
                        <tr>
                            <th scope="col">달</th>
                            {TRIBUTE_RESOURCES.map((r) => <th key={r.key} scope="col">{r.label}</th>)}
                            <th scope="col">상태</th>
                        </tr>
                    </thead>
                    <tbody>
                        {c.tributeHistory.map((r) => {
                            const st = receiptStatus(r);
                            return (
                                <tr key={`${r.year}-${r.month}`}>
                                    <td>{r.year}년 {r.month}월</td>
                                    {TRIBUTE_RESOURCES.map(({ key }) => (
                                        <td key={key} className={`os-num ${r.unpaid[key] > 0 ? styles.unpaid : ''}`.trim()}>
                                            {r.unpaid[key] > 0 ? `${r.paid[key]} / ${r.due[key]}` : r.paid[key]}
                                        </td>
                                    ))}
                                    <td>{st === 'UNPAID' ? <Chip tone="rust">미납</Chip> : st === 'PAID' ? <Chip tone="moss">완납</Chip> : <Chip>청구 없음</Chip>}</td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>
        </div>
    );
}

function WaitText({ children }: { readonly children: ReactNode }) {
    return <span className={styles.waitText} data-server-wait="K8-04">{children}</span>;
}

function Wait({ row, title, body }: { readonly row: string; readonly title: string; readonly body: ReactNode }) {
    return (
        <div className={styles.wait} data-server-wait={row}>
            <StatusView kind="waiting" title={title} body={body} />
        </div>
    );
}
