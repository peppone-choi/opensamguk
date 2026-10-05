import { NotFoundScreen } from '@/components/states/PageStates';

/**
 * 게임 화면이 직접 notFound() 를 부를 때(찾는 대상이 없음) — 셸(머리줄 · 레일 · 하단 탭)은 그대로 두고 본문만
 * 「찾는 화면이 없습니다」(보드 V31SystemStates · MNotFound).
 * 주의: AuthGate 가 서버 렌더에서 화면을 그리지 않아 여기 오는 응답은 HTTP 200 이다. 그래서 맞는 화면이 없는 주소는
 * catch-all 로 여기 끌어오지 않고 뿌리 app/not-found.tsx(진짜 404)가 받는다(셸 스모크 「없는 화면 … 404」).
 */
export default function GameNotFound() {
    return <NotFoundScreen />;
}
