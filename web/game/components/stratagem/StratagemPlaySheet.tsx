'use client';

// 계책 쓰기 · 걸기(P-S02) — 보드 V31K6Stratagem(데스크톱 · 태블릿: 오른쪽 겹침 패널 480) · MStratagem(모바일: 하단 시트), K6 설계서 §3.3.
// 덱(P-S01)의 「쓰기 · 걸기」가 `?card=<instanceId>`로 연다. 손패 줄(다른 카드로 바꾸기) → 카드(그림 · 이름 · 방식 · 효과) → 대상 칸(방식별)
// → 비용 · 내는 곳 · 판정 → 「쓰기」(즉시 · 설치) / 「걸기」(대응).
// 서버가 지금 주는 것은 손패(카드 종류 · 이름)뿐이다. 대상 후보(targets[]) · 비용 · 사거리는 C1이 처리기 · 옵션을 넣을 때까지 서버 대기
// (계약판 K6-10 · A4 · A10)이고, 카드 쓰기(stratagem.play)는 원장 PLANNED라 아래 단추가 「준비 중」이다(누르면 사유). 그래서 지도 위
// 사거리 원 · 후보 표지는 그리지 않는다(지어낸 대상 없음). 후보가 오면 대상 칸을 공용 TargetCandidateList로 채운다.
import { useCallback, useEffect, useRef, type KeyboardEvent } from 'react';
import { StatusView } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { ShellIcon } from '@/components/shell/ShellIcon';
import { availabilityOf } from '@/lib/input-availability';
import { playActionLabel, playHelp, playTitle, targetPrompt, type CardMode, type HandCardView, type HandView } from '@/lib/stratagem/hand';
import styles from './Stratagem.module.css';

export interface StratagemPlaySheetProps {
    readonly hand: HandView;
    /** 주소의 카드(parseCardParam). 형식이 틀리면 null — 「손패에 없는 카드」로 그린다. */
    readonly cardId: number | null;
    /** 손패 줄에서 다른 카드를 고른다(주소의 ?card= 를 바꾼다). */
    readonly onPick: (instanceId: number) => void;
    /** 닫기 · Esc — ?card= 를 뺀 덱으로 돌아간다. */
    readonly onClose: () => void;
    readonly onRetry: () => void;
}

const MODE_TONE: Record<CardMode, string> = { 즉시: 'os-chip--bronze', 설치: 'os-chip--info', 대응: 'os-chip--moss' };

export function StratagemPlaySheet({ hand, cardId, onPick, onClose, onRetry }: StratagemPlaySheetProps) {
    const cards = hand.state === 'ready' ? hand.cards : [];
    const card = cards.find((c) => c.instanceId === cardId) ?? null;
    const title = playTitle(card);
    const root = useRef<HTMLElement>(null);

    // 열리면 시트로 초점을 옮긴다 — 키보드 사용자가 바로 Esc · Tab 으로 다룰 수 있게. 카드를 바꿔도 시트 안에 둔다.
    useEffect(() => {
        if (root.current && !root.current.contains(document.activeElement)) root.current.focus();
    }, [cardId]);

    // Esc — 닫는다. 조합 중 · 안쪽이 먼저 받았으면(사유 시트 preventDefault) · 대화 안에서 난 Esc 는 두고(Esc 한 번에 한 겹),
    // 닫을 때는 받았다고 표시해 셸 층이 겹쳐 닫지 않게 한다.
    const onKeyDown = useCallback((event: KeyboardEvent<HTMLElement>) => {
        if (event.key !== 'Escape' || event.nativeEvent.isComposing || event.defaultPrevented) return;
        if (event.target instanceof Element && event.target.closest('[role="dialog"]')) return;
        event.preventDefault();
        onClose();
    }, [onClose]);

    return (
        <aside ref={root} className={styles.play} aria-label={title} data-testid="stratagem-play" tabIndex={-1} onKeyDown={onKeyDown}>
            <div className={styles.playHead}>
                <h2 className={styles.playTitle}>{title}</h2>
                <span className={styles.muted}>손패에서 한 장</span>
                <button type="button" className={styles.playClose} aria-label="닫기(Esc)" onClick={onClose}>
                    <ShellIcon name="close" />
                </button>
            </div>
            <div className={styles.playBody}>
                {hand.state === 'loading' ? <StatusView kind="loading" rows={3} /> : null}
                {hand.state === 'error' ? (
                    <StatusView kind="error" title={hand.message} body="빈 손패가 아닙니다 — 불러오기가 실패했습니다." errorCode={hand.code} onRetry={onRetry} />
                ) : null}
                {hand.state === 'first-draw' ? <StatusView kind="empty" title="아직 첫 손패를 받지 않았습니다" body="다음 개인 턴에 받습니다." /> : null}
                {hand.state === 'ready' && cards.length === 0 ? <StatusView kind="empty" title="쓸 카드가 없습니다" body="다음 개인 턴에 한 장 뽑습니다." /> : null}
                {cards.length > 0 ? (
                    <div className={styles.playHand} role="group" aria-label="손패">
                        {cards.map((c) => (
                            <button key={c.instanceId} type="button" className={styles.playThumb} aria-pressed={c.instanceId === card?.instanceId} onClick={() => onPick(c.instanceId)}>
                                {c.art ? (
                                    // eslint-disable-next-line @next/next/no-img-element -- 정본 export 그대로(게이트웨이가 서빙)
                                    <img src={c.art} alt="" width={60} height={60} loading="lazy" decoding="async" />
                                ) : null}
                                <span>{c.label}</span>
                            </button>
                        ))}
                    </div>
                ) : null}
                {cards.length > 0 && !card ? (
                    <StatusView kind="empty" title="그 카드는 손패에 없습니다" body="이미 썼거나 손패가 바뀌었습니다. 위 손패에서 고르세요." />
                ) : null}
                {card ? <PlayCard card={card} /> : null}
            </div>
        </aside>
    );
}

function PlayCard({ card }: { readonly card: HandCardView }) {
    const help = playHelp(card.mode);
    const prompt = targetPrompt(card.mode);
    return (
        <>
            <div className={styles.playCard}>
                {card.art ? (
                    // eslint-disable-next-line @next/next/no-img-element -- 정본 export 그대로(게이트웨이가 서빙)
                    <img className={styles.playArt} src={card.art} alt={`${card.label} 카드 그림`} width={96} height={144} decoding="async" />
                ) : null}
                <div className={styles.playCardText}>
                    <h3 className={styles.playName}>{card.label}</h3>
                    {card.mode ? <span className={`os-chip ${MODE_TONE[card.mode]}`}>{card.mode}</span> : null}
                    {card.effect ? <p className={styles.cardEffect}>{card.effect}</p> : null}
                </div>
            </div>
            {help ? <p className={styles.playHelp}>{help}</p> : null}
            <section className={styles.playTarget} aria-label={prompt}>
                <h3 className={styles.playLabel}>{prompt}</h3>
                <StatusView kind="waiting" title="대상 후보 준비 중" body={`고를 수 있는 ${card.mode === '대응' ? '방어 칸' : '대상'}은 서버가 아직 주지 않습니다.`} />
            </section>
            <dl className={styles.playFacts}>
                <div><dt>비용</dt><dd>서버 대기</dd></div>
                <div><dt>내는 곳</dt><dd>쓰는 곳의 보급망 창고</dd></div>
                <div><dt>판정</dt><dd>쓰는 인물 지력 대 상대 지력</dd></div>
            </dl>
            <div className={styles.playFoot}>
                <HelpedInputAction
                    inputId="stratagem.play"
                    availability={availabilityOf('stratagem.play')}
                    label={playActionLabel(card)}
                    onAct={() => {}}
                    reasonTitle="계책 쓰기 — 아직 열리지 않았습니다"
                />
            </div>
        </>
    );
}

export default StratagemPlaySheet;
