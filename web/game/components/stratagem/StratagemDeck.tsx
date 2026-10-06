'use client';

// 계책 덱(P-S01) 본문 — 보드 V31K6Hand · MHand. 페이지 틀(머리 · 하위 탭)은 셸이 준다.
// 위: 즉시 · 설치 · 대응 세 칸 / 가운데: 손패(누르면 고름 — 끌기 없음) + 고른 카드의 「쓰기 · 걸기」 / 오른쪽: 덱 기여 · 지난 발동.
// 「쓰기 · 걸기」는 계책 쓰기 시트(P-S02, `?card=`)를 연다(설계서 §3.2 「『쓰기』는 P-S02를 연다」). 카드 쓰기 입력(stratagem.play)은
// 원장 PLANNED라 시트 아래 결정 단추가 「준비 중」이고, 그래서 세 칸에는 걸린 카드가 있을 수 없다(비어 있음).
// 덱 기여 · 지난 발동은 읽기가 없어 서버 대기(StatusView waiting — 계약판 K6-10).
import { useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import { actionLabel, CARD_MODES, type CardMode, type HandCardView, type HandView } from '@/lib/stratagem/hand';
import styles from './Stratagem.module.css';

export interface StratagemDeckProps {
    readonly hand: HandView;
    readonly onRetry: () => void;
    /** 고른 카드로 계책 쓰기 시트를 연다(주소 ?card=). 없으면 단추를 그리지 않는다. */
    readonly onOpen?: (instanceId: number) => void;
}

const MODE_TONE: Record<CardMode, string> = { 즉시: 'os-chip--bronze', 설치: 'os-chip--info', 대응: 'os-chip--moss' };
const ZONE_SUB: Record<CardMode, string> = { 즉시: '내 턴에 공개', 설치: '숨겨 깐다 · 조건이 맞으면 발동', 대응: '방어 칸 · 공격받을 때 공개' };

export function StratagemDeck({ hand, onRetry, onOpen }: StratagemDeckProps) {
    const cards = hand.state === 'ready' ? hand.cards : [];
    const [picked, setPicked] = useState<number | null>(null);
    const card = cards.find((c) => c.instanceId === picked) ?? cards[0] ?? null;

    return (
        <div className={styles.deck} data-testid="stratagem-deck">
            <div className={styles.main}>
                <div className={styles.zones}>
                    {CARD_MODES.map((m) => (
                        <section key={m} className={styles.zone} aria-label={`${m} 칸`}>
                            <h3 className={styles.zoneHead}><span className={`os-chip ${MODE_TONE[m]}`}>{m}</span><span>{ZONE_SUB[m]}</span></h3>
                            <p className={styles.zoneEmpty}>비어 있음</p>
                        </section>
                    ))}
                </div>

                <section className={styles.hand} aria-label="손패">
                    <h3 className={styles.handHead}>
                        손패{hand.state === 'ready' ? <span className="os-chip os-chip--bronze">{`손패 ${hand.cards.length} / ${hand.limit}`}</span> : null}
                        <span className={styles.muted}>자기 턴마다 한 장 뽑는다</span>
                    </h3>
                    {hand.state === 'loading' ? <StatusView kind="loading" rows={2} /> : null}
                    {hand.state === 'error' ? (
                        <StatusView kind="error" title={hand.message} body="빈 손패가 아닙니다 — 불러오기가 실패했습니다." errorCode={hand.code} onRetry={onRetry} />
                    ) : null}
                    {hand.state === 'first-draw' ? (
                        <StatusView kind="empty" title="아직 첫 손패를 받지 않았습니다" body="다음 개인 턴에 받습니다." />
                    ) : null}
                    {hand.state === 'ready' && cards.length === 0 ? (
                        <StatusView kind="empty" title="손패가 비었습니다" body="다음 개인 턴에 한 장 뽑습니다." />
                    ) : null}
                    {cards.length > 0 ? (
                        <>
                            <div className={styles.cards} role="listbox" aria-label="손패 카드">
                                {cards.map((c) => <HandCard key={c.instanceId} card={c} selected={c.instanceId === card?.instanceId} onPick={() => setPicked(c.instanceId)} />)}
                            </div>
                            {card ? (
                                <div className={styles.actions}>
                                    {onOpen ? (
                                        <button type="button" className="os-button" onClick={() => onOpen(card.instanceId)}>{actionLabel(card)}</button>
                                    ) : null}
                                    <span className={styles.muted}>비용은 쓰는 곳의 보급망 창고에서 · 판정은 쓰는 인물 지력 대 상대 지력</span>
                                </div>
                            ) : null}
                        </>
                    ) : null}
                </section>
            </div>

            <aside className={styles.side}>
                <section className={styles.sideBox} aria-label="덱 — 누가 어떤 카드를 넣었나">
                    <h3 className={styles.sideHead}>덱 — 누가 어떤 카드를 넣었나</h3>
                    <StatusView kind="waiting" title="덱 기여 읽기 준비 중" body="인물별로 넣은 카드는 서버가 아직 주지 않습니다." />
                </section>
                <section className={styles.sideBox} aria-label="지난 발동">
                    <h3 className={styles.sideHead}>지난 발동</h3>
                    <StatusView kind="waiting" title="발동 기록 준비 중" body="계책 입력이 열리면 기록의 사건으로 보입니다." />
                </section>
            </aside>
        </div>
    );
}

function HandCard({ card, selected, onPick }: { card: HandCardView; selected: boolean; onPick: () => void }) {
    return (
        <button type="button" role="option" aria-selected={selected} className={styles.card} onClick={onPick} data-instance-id={card.instanceId}>
            <span className={styles.cardTop}>{card.mode ? <span className={`os-chip ${MODE_TONE[card.mode]}`}>{card.mode}</span> : null}</span>
            {card.art ? (
                // eslint-disable-next-line @next/next/no-img-element -- 정본 export 그대로(최적화 서버 없이 게이트웨이가 서빙)
                <img className={styles.cardArt} src={card.art} alt={`${card.label} 카드 그림`} width={154} height={231} loading="lazy" decoding="async" />
            ) : null}
            <span className={styles.cardName}>{card.label}</span>
            {card.effect ? <span className={styles.cardEffect}>{card.effect}</span> : null}
        </button>
    );
}

export default StratagemDeck;
