'use client';

// 지도 미리보기(MapPreview)의 옛 지도판과 새 지도(TopdownMapPreview)가 같이 쓰는 조각:
// 이름 단추 · 누른 城 이름표 · 자리 표시. 두 지도가 같은 글자 · 같은 단추를 보이게 한 곳에 둔다.
import { cityBadgeLabel, cityDisplayName, isUprisingNation, WATERWAY_SITE_ROLES, type IsoCityOverlay } from '@opensamguk/ui';
import { useCallback, useEffect, useState } from 'react';

// 레이어 「이름」 켜고 끄기(설계서 MP5). 옛 삼모 키(sam.hideMapCityName)는 쓰지 않는다.
const LS_HIDE_NAMES = 'opensamguk.map.hideNames';
/** 주인 없는 城 — 공용 지도는 「공백지」라 적는다. 화면은 v3 범례 말 「무주」로 보인다(설계서 MP7). */
const NEUTRAL_LABEL = '무주';
const SHARED_NEUTRAL_NAME = '공백지';

/** 이름 보이기 설정(브라우저에 남긴다). 저장소를 못 읽으면(사생활 창 등) 이름을 보인다. */
export function useHideCityNames(): [boolean, () => void] {
    const [hidden, setHidden] = useState(false);
    useEffect(() => {
        try {
            setHidden(window.localStorage.getItem(LS_HIDE_NAMES) === 'yes');
        } catch {
            /* 저장소를 못 읽으면 이름을 보인다 */
        }
    }, []);
    const toggle = useCallback(() => {
        setHidden((was) => {
            try {
                window.localStorage.setItem(LS_HIDE_NAMES, was ? 'no' : 'yes');
            } catch {
                /* 저장하지 못해도 이번 화면에서는 바뀐다 */
            }
            return !was;
        });
    }, []);
    return [hidden, toggle];
}

export function mapPreviewRootClass(backdrop: boolean, hideCityName: boolean): string {
    return `map-preview${backdrop ? ' map-preview--backdrop' : ''}${hideCityName ? ' hide-cityname' : ''}`;
}

export function NameToggle({ hidden, onToggle }: { hidden: boolean; onToggle: () => void }) {
    return (
        <div className="map-btn-stack">
            <button
                type="button"
                className={`map-toggle-cityname${hidden ? '' : ' active'}`}
                aria-pressed={!hidden}
                aria-label="지도 이름 보이기"
                onClick={onToggle}
            >
                이름
            </button>
        </div>
    );
}

/** 얹으면 커서를 따라오고(`at`), 누르면 왼위에 붙는다(손가락에는 hover 가 없다). */
export function CityTooltip({ city, at }: { city: IsoCityOverlay; at?: { x: number; y: number } | null }) {
    // 주인 없는 城은 「무주」. 공용 지도가 붙인 「공백지」도 같은 뜻이다.
    const nation = city.nationName && city.nationName !== SHARED_NEUTRAL_NAME ? city.nationName : undefined;
    return (
        <div
            className={`map-preview-tooltip${at ? '' : ' map-preview-tooltip--pinned'}`}
            role="status"
            style={at ? { left: at.x + 14, top: at.y + 14 } : undefined}
        >
            <div className="map-preview-tooltip-name">{cityDisplayName(city)}</div>
            <div className="map-preview-tooltip-meta">
                {isUprisingNation(nation) ? '봉기 세력 · ' : ''}
                {nation ?? NEUTRAL_LABEL}
                {city.isCapital ? ' · 수도' : ''}
            </div>
            {(city.cityBadges ?? []).map((badge, index) => (
                <div className="map-preview-tooltip-meta" key={`state-${index}`}>{cityBadgeLabel(badge)}</div>
            ))}
            {(WATERWAY_SITE_ROLES[city.id] ?? []).map((feature) => (
                <div className="map-preview-tooltip-meta" key={feature}>{feature === 'port' ? '항구' : '나루'}</div>
            ))}
        </div>
    );
}

export function MapPreviewLoading({ rootClass }: { rootClass: string }) {
    return (
        <div className={rootClass} aria-label="서버 지도">
            <div className="map-preview-ph" role="status"><div className="spinner" aria-hidden="true" />지도를 불러오는 중</div>
        </div>
    );
}

export function MapPreviewFailed({ rootClass, why }: { rootClass: string; why?: string }) {
    return (
        <div className={rootClass} aria-label="서버 지도">
            <div className="map-preview-ph" role="status">
                지도를 불러오지 못했습니다
                {why && <span className="map-preview-ph__why">{why}</span>}
            </div>
        </div>
    );
}
