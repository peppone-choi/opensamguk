// 보루 지명 — 서버가 준 RoadFortDto.provinceName만 사람 말로 옮긴다.
//
// 이름이 없거나(옛 서버 · null) 공백뿐이면 내부 구역 id를 보이지 않고 「장소 이름 확인 불가」로 둔다.
// 도시 맞춤 · 지도 읽기로 이름을 추정하지 않는다.
import type { RoadFort } from './campaign-reads';

const ROAD_FORT_UNKNOWN_PLACE = '장소 이름 확인 불가';

/** 보루 후보 이름 — 예: 「보루 · 호뢰관」. 고르는 값(fort.id)과는 무관하다. */
export function roadFortLabel(fort: Pick<RoadFort, 'provinceName'>): string {
    const name = typeof fort.provinceName === 'string' ? fort.provinceName.trim() : '';
    return `보루 · ${name || ROAD_FORT_UNKNOWN_PLACE}`;
}
