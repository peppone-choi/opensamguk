'use client';

// 관직 · 봉신 › 지방 관직(P-K03) — 보드 V31K8Offices(관할과 앉은 사람 · 고른 관할 · 받은 임명 제안) · OfficesLord(임명할 수 있는 자리 · 보낸 임명 제안) · MOffices.
// 지방 관직 읽기(K8-03, GET /api/court/local-offices)를 미리 지어 둔다(D124 A) — 서버가 아직 경로를 내지 않으면(404) 지금처럼
// 서버 대기(data-server-wait K8-03)이고, 200 이 오면 값이 저절로 나온다. 받은 임명 제안은 K8-02(C5 #1383) 몫이라 서버 대기로 둔다.
// 입력(court.appoint · court.dismiss)은 입력 원장에 행이 없어 그리지 않는다(「원장 행 없음 = 그리지 않음」). 서버 계약의 이름 빈칸
// (보낸 제안의 후보 · 관할 이름)은 같은 응답에서 찾고, 못 찾으면 「준비 중」 서버 대기로 둔다.
import { useState, type ReactNode } from 'react';
import { Chip, Icon, KV, Panel, SectionHeader, StatusView } from '@opensamguk/ui';
import { useGameSession } from '@/lib/campaign-session';
import { evidenceRows, type OfferRow, type OptionRow, type TenureRow } from '@/lib/court-local-offices-view';
import { useCourtLocalOffices } from '@/lib/use-court-local-offices';
import shared from './offices.module.css';
import styles from './localOffices.module.css';

export default function LocalOfficesTab() {
    const { generalId } = useGameSession();
    const read = useCourtLocalOffices(generalId);
    const [picked, setPicked] = useState<string | null>(null);
    const view = read.state === 'ready' ? read.view : null;
    const rows = view?.kind === 'offices' ? view.rows : [];
    const selected = rows.find((r) => r.tenureId === picked) ?? rows[0];

    return (
        <div className={shared.split}>
            <Panel className={shared.box} aria-label="관할과 앉은 사람">
                <SectionHeader title="관할과 앉은 사람" sub="주 → 군국" />
                <div className={shared.legend} aria-label="상태 풀이">
                    <Chip tone="moss">실권 있음</Chip>
                    <Chip tone="rust">명목</Chip>
                    <Chip tone="info">부임 전</Chip>
                    <Chip tone="info">수락 대기</Chip>
                    <span className={shared.note}>현령은 배치 · 발령으로 정합니다</span>
                </div>
                {read.state === 'loading' ? <StatusView kind="loading" rows={4} /> : null}
                {read.state === 'waiting' ? (
                    <Waiting row="K8-03" title="관직 정보가 아직 없습니다" body="관할마다 누가 앉았고 실제로 다스리는지는 서버가 아직 주지 않습니다. 준비되면 이 자리에 보입니다." />
                ) : null}
                {read.state === 'error' ? (
                    <StatusView kind="error" title="관직 정보를 지금 읽을 수 없습니다" body="잠시 뒤 다시 해 보세요." onRetry={read.retry} />
                ) : null}
                {view?.kind === 'unavailable' ? (
                    <StatusView kind="unavailable" title="관직 정보를 셈하지 못했습니다" body="서버가 지금 관직을 셈하지 못했습니다. 관직이 없다는 뜻은 아닙니다." onReload={read.retry} />
                ) : null}
                {view?.kind === 'empty' ? <StatusView kind="empty" title="지방 관직이 없습니다" body="이 세력에는 아직 앉은 지방 관직이 없습니다." /> : null}
                {view?.kind === 'offices' ? (
                    <>
                        <div className={styles.head} aria-hidden="true">
                            <span>관할</span>
                            <span>관직</span>
                            <span>앉은 사람</span>
                            <span>상태</span>
                            <span>실효 현</span>
                        </div>
                        <ul className={styles.rows} aria-label="지방 관직">
                            {view.rows.map((r) => (
                                <li key={r.tenureId}>
                                    <Row row={r} pressed={selected?.tenureId === r.tenureId} onPick={() => setPicked(r.tenureId)} />
                                </li>
                            ))}
                        </ul>
                        <p className={`${shared.foot} ${styles.foot}`}>
                            <span>{`수락 대기 ${view.counts.pending} · 부임 전 ${view.counts.awaiting} · 명목 ${view.counts.nominal}`}</span>
                            <span>다른 세력의 관직은 보이지 않습니다. 관직을 둘 수 없는 관할(치소를 모르는 곳)은 목록에 없습니다.</span>
                        </p>
                    </>
                ) : null}
            </Panel>
            <div className={shared.col}>
                <Panel className={shared.box} aria-label="고른 관할">
                    {selected ? (
                        <Detail row={selected} />
                    ) : (
                        <>
                            <SectionHeader title="고른 관할" sub="앉은 사람 · 실효 판정" />
                            {view === null || view.kind === 'unavailable' ? (
                                <Waiting row="K8-03" title="아직 없습니다" body="관할을 고르면 앉은 사람과 실제로 다스리는지가 여기 보입니다. 서버가 아직 주지 않습니다." />
                            ) : (
                                <StatusView kind="empty" title="고를 관할이 없습니다" body="앉은 지방 관직이 생기면 여기서 고를 수 있습니다." />
                            )}
                        </>
                    )}
                </Panel>
                <Panel className={shared.box} aria-label="받은 임명 제안">
                    <SectionHeader title="받은 임명 제안" sub="응답은 장수 행동을 쓰지 않습니다" />
                    <Waiting row="K8-02" title="아직 없습니다" body="받은 임명 제안은 서버가 아직 주지 않습니다." />
                </Panel>
                {view && view.kind !== 'unavailable' && view.options.length > 0 ? <Options options={view.options} /> : null}
                {view && view.kind !== 'unavailable' && view.offers.length > 0 ? <Offers offers={view.offers} /> : null}
            </div>
        </div>
    );
}

function Row({ row, pressed, onPick }: { readonly row: TenureRow; readonly pressed: boolean; readonly onPick: () => void }) {
    return (
        <button type="button" className={styles.row} aria-pressed={pressed} onClick={onPick} data-depth={row.depth}>
            <span className={styles.place}>
                <span className={styles.placeName}>{row.jurisdiction}</span>
                {row.seat ? <Chip tone="bronze">{row.seat}</Chip> : null}
            </span>
            <span className={styles.office}>{row.office}</span>
            <span className={styles.holder}>{row.holder}</span>
            <span>
                <Chip tone={row.chip.tone}>{row.chip.label}</Chip>
            </span>
            <span className={styles.effective}>{row.effective}</span>
        </button>
    );
}

function Detail({ row }: { readonly row: TenureRow }) {
    const t = row.tenure;
    return (
        <>
            <SectionHeader title={`${row.jurisdiction} ${row.office}`} sub={[row.region, row.seat].filter(Boolean).join(' · ') || undefined} />
            {t.state === 'NOMINAL' ? (
                <p className={styles.warn} role="note">
                    명목입니다 — 이 자리로 할 수 있는 일이 없습니다. 아래 실효 판정에서 부족한 근거를 보세요.
                </p>
            ) : null}
            <div className={shared.detail}>
                <KV
                    items={[
                        { k: '앉은 사람', v: row.holder },
                        { k: '상태', v: row.chip.label },
                        { k: '실효 현', v: row.effective },
                    ]}
                />
            </div>
            <p className={styles.evidenceHead}>실효 판정 — 모두 맞아야 실권이 있습니다</p>
            <ul className={styles.evidence} aria-label="실효 판정">
                {evidenceRows(t).map((e) => (
                    <li key={e.code} className={e.ok ? styles.ok : styles.miss}>
                        <Icon name={e.ok ? 'check' : 'close'} size={16} />
                        <span>{e.text}</span>
                        <span className="sr-only">{e.ok ? ' — 맞음' : ' — 부족함'}</span>
                    </li>
                ))}
            </ul>
            <p className={`${shared.foot} ${styles.foot}`}>
                <span>파면 · 임명은 입력이 준비되면 여기서 합니다.</span>
            </p>
        </>
    );
}

function Options({ options }: { readonly options: readonly OptionRow[] }) {
    return (
        <Panel className={shared.box} aria-label="임명할 수 있는 자리">
            <SectionHeader title="임명할 수 있는 자리" sub={`${options.length}곳 · 서버가 준 목록`} />
            <ul className={styles.list}>
                {options.map((o) => (
                    <li key={o.key} className={styles.item}>
                        <span className={styles.itemMain}>
                            <span className={styles.itemName}>{`${o.jurisdiction} ${o.office}`}</span>
                            <span className={styles.itemSub}>{`후보 ${o.candidate}`}</span>
                        </span>
                        {o.available ? <Chip tone="moss">임명할 수 있음</Chip> : <span className={styles.reason}>{o.reason}</span>}
                    </li>
                ))}
            </ul>
            <p className={`${shared.foot} ${styles.foot}`}>
                <span>임명 입력은 아직 준비 중입니다. 막힌 자리는 서버가 준 이유를 그대로 보입니다.</span>
            </p>
        </Panel>
    );
}

function Offers({ offers }: { readonly offers: readonly OfferRow[] }) {
    return (
        <Panel className={shared.box} aria-label="보낸 임명 제안">
            <SectionHeader title="보낸 임명 제안" sub={`${offers.length}건`} />
            <ul className={styles.list}>
                {offers.map((o) => (
                    <li key={o.offerId} className={styles.item}>
                        <span className={styles.itemMain}>
                            <span className={styles.itemName}>
                                {o.candidate ?? <WaitText>후보 이름 준비 중</WaitText>}
                                {' — '}
                                {o.jurisdiction ? `${o.jurisdiction} ${o.office}` : <>{o.office} <WaitText>관할 이름 준비 중</WaitText></>}
                            </span>
                            <span className={styles.itemSub}>{`기한 ${o.due}`}</span>
                        </span>
                        <Chip tone={o.chip.tone}>{o.chip.label}</Chip>
                    </li>
                ))}
            </ul>
        </Panel>
    );
}

/** 서버 계약에 없는 이름 칸 — K8-03 대기로 표시한다(값을 짓지 않는다). */
function WaitText({ children }: { readonly children: ReactNode }) {
    return (
        <span className={shared.waitText} data-server-wait="K8-03">
            {children}
        </span>
    );
}

/** 서버 대기 A — 영역 전체 waiting. 기다리는 계약판 행을 data-server-wait 에 단다. */
function Waiting({ row, title, body }: { readonly row: string; readonly title: string; readonly body: ReactNode }) {
    return (
        <div className={shared.wait} data-server-wait={row}>
            <StatusView kind="waiting" title={title} body={body} />
        </div>
    );
}
