'use client';

// Gauge — 재사용 now/max 진행 막대(레거시 SammoBar.vue + MapCityDetail의 `mcd-bar` 패턴 그대로).
// 레거시 CityBasicCard.vue가 도시 수치(주민/농업/상업/치안/수비/성벽)를 `<SammoBar percent=(now/max)*100 />`
// + `<div class="cellText">now / max</div>` 로 그리는 것을 충실 이식한다. 막대 채움 = (now/max)*100 을
// 0..100 으로 클램프(레거시 SammoBar의 lodash clamp 동치), 텍스트 = `now.toLocaleString() / max.toLocaleString()`.
//
// 스타일은 이미 globals.css 에 존재하는 `mcd-bar`/`mcd-bar-fill`/`mcd-metric-*` 클래스를 공유해
// MapCityDetail 패널과 픽셀 단위로 동일하게 보이도록 한다(새 그라데이션/둥근모서리 없음 — 레거시 dense UI 유지).
//
// 주의(패러티): max 가 없는 수치(국가 금/쌀, 장수 통/무/지)는 Gauge 로 그리지 않는다. 레거시에서 그 값들은
// 막대 없는 평문이거나(국가 금/쌀) 별도 경험치 막대(장수 통/무/지의 *_exp)이며 web/game DTO 엔 그 분모가
// 없다. 분모를 날조하지 않는다 — 그런 값은 호출부에서 평문으로 렌더한다.

import SammoBar from './SammoBar';

interface GaugeProps {
    /** 행 라벨(예: '인구', '농업'). 레거시 gHead 텍스트와 동일하게 전달한다. */
    label: string;
    /** 현재값(now). */
    now: number;
    /**
     * 최댓값(max). 막대 채움 비율의 분모. 막대 텍스트는 `now / max` 형식으로 렌더한다.
     * barOnly=true 이면 분모는 막대 채움 계산에만 쓰고 텍스트엔 표시하지 않는다(민심형 단독 숫자).
     */
    max: number;
    /**
     * true 면 텍스트를 `now / max` 가 아니라 now 단독으로 렌더(레거시 민심: 소수 최대 1자리, '%' 없음).
     * 막대는 그대로 (now/max)*100 으로 채운다.
     */
    barOnly?: boolean;
}

export default function Gauge({ label, now, max, barOnly = false }: GaugeProps) {
    // 막대 채움 % — (now/max)*100, 0..100 클램프. max<=0 이면 0(레거시 NaN 가드 동치).
    const pct = max > 0 ? Math.min(100, Math.max(0, (now / max) * 100)) : 0;

    return (
        <div className="mcd-metric gauge-metric">
            <div className="mcd-metric-head">{label}</div>
            <div className="mcd-metric-body">
                <SammoBar percent={pct} height={7} />
                <div className="mcd-metric-text">
                    {barOnly
                        ? now.toLocaleString(undefined, { maximumFractionDigits: 1 })
                        : `${now.toLocaleString()} / ${max.toLocaleString()}`}
                </div>
            </div>
        </div>
    );
}
