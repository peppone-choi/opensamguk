'use client';

/**
 * 서버 세계지도 프리뷰 (서버마다 10분 캐싱).
 *
 * 설계(결정 로그 §9): game-engine이 10분마다 맵 스냅샷 JSON(도시 좌표·국가색·레벨)을
 * 캐시 → game-api `GET /api/map/preview` 서빙 → 여기서 opensam-images CDN 추상 게임맵
 * 베이스 위에 클라이언트 SVG로 도시점을 렌더(국가색 dot, hover=도시명/레벨). 좌표는
 * 시나리오 scenario/map 게임 x/y.
 *
 * F0: 백엔드 맵 엔드포인트가 아직 없어 placeholder만 렌더. F1(시드)·맵 엔드포인트 도착 후
 * gameApiUrl로 fetch하여 SVG 도시점을 그린다.
 */
export default function MapPreview({ gameApiUrl }: { gameApiUrl?: string }) {
    // TODO(F0-map): gameApiUrl + '/api/map/preview'에서 10분 캐시 맵 데이터 fetch →
    // CDN 베이스맵 <img> + <svg> 도시점(국가색) 렌더.
    void gameApiUrl;
    return (
        <div className="map-preview" aria-label="서버 지도 프리뷰">
            <div className="map-preview-ph">맵 프리뷰 (서버별 10분 캐싱 — 준비 중)</div>
        </div>
    );
}
