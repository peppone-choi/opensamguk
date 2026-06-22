'use client';

import Shell from '../../components/Shell';
import GameChrome from '../../components/game/GameChrome';
import MainRecordZone from '../../components/game/MainRecordZone';

/**
 * /game 메인 화면 (spec §1.1 PageFront). GameChrome 가 chrome spine(GameInfo + GlobalMenu +
 * MainControlBar)에 더해 맵 + 예약명령 + info 카드(general/nation/city)까지 모두 그린다. hasGeneral=false
 * 면 서버별 장수 생성 페이지로 이동한다.
 *
 * 메인 라우트는 PageFront RecordZone(장수 동향·개인 기록·중원 정세)을 children 으로 넘긴다.
 */
export default function GameMainPage() {
    return (
        <Shell>
            <GameChrome>{(frontInfo) => <MainRecordZone recentRecord={frontInfo.recentRecord} />}</GameChrome>
        </Shell>
    );
}
