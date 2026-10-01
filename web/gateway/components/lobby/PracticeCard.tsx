import { Chip } from '@opensamguk/ui';

/**
 * 「첫걸음 — 연습 서버」 카드(K7 설계서 §5.1, K0 결정: 게이트웨이엔 도움말 패널 없이 이 카드만). 서버 목록 맨 위.
 * 연습 서버를 가리는 표지(계약판 K7-03: 서버 목록 kind 또는 /api/tutorial/world)와 진척(K7-02)이 아직 없어
 * 「연습 서버 준비 중」 상태로만 그린다. 표지가 오면 진척 없음 · 이어 하기 n / 8 · 마침(한 줄로 접힘) 세 상태를 그린다.
 */
export default function PracticeCard() {
    return (
        <section className="os-panel os-panel--static gw31-practice" aria-labelledby="practice-title">
            <div className="gw31-practice__text">
                <h2 id="practice-title" className="gw31-practice__title os-serif">첫걸음 — 연습 서버</h2>
                <p className="gw31-card__line">장수를 만들고 첫 출사부터 첫 전투까지 여덟 걸음을 빠르게 흐르는 연습 서버에서 해 봅니다. 여기서 만든 장수는 본 서버로 넘어가지 않습니다.</p>
            </div>
            <div className="gw31-practice__state" role="status">
                <Chip tone="info">준비 중</Chip>
                <span>연습 서버 준비 중</span>
            </div>
        </section>
    );
}
