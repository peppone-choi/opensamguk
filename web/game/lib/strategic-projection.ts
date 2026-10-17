// 전략 지도 칸 → 화면 투영(AOE2풍 단기 전환 B4). 빌드 기능 플래그 NEXT_PUBLIC_STRATEGIC_PROJECTION 이 'dimetric' 이면 2:1 다이메트릭,
// 비어 있으면(기본) 현행 탑다운이다. 칸 좌표 · 이동 · 판정 · 시야 · 명령 · 서버 계약은 투영과 상관없이 같다(그리기만 바뀐다).
// 지금 이 값을 쓰는 화면: 작전실 지도 · 명령 목적지 지도 · 지도 시험(/map-lab). 나머지 지도 화면은 탑다운 그대로다.
import { strategicProjection } from '@opensamguk/ui/map/topdown';

export const STRATEGIC_PROJECTION = strategicProjection(process.env.NEXT_PUBLIC_STRATEGIC_PROJECTION);
