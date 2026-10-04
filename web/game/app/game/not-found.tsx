import { NotFoundScreen } from '@/components/states/PageStates';

/** /game 안의 없는 화면 — 셸(머리줄 · 레일 · 하단 탭)은 그대로 두고 본문만 「찾는 화면이 없습니다」(보드 V31SystemStates · MNotFound). */
export default function GameNotFound() {
    return <NotFoundScreen />;
}
