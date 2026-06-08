'use client';

import Shell from '../../components/Shell';
import GameChrome from '../../components/game/GameChrome';
import MyInfoLogPanel from '../../components/game/MyInfoLogPanel';
import { useFrontInfo } from '../../hooks/useFrontInfo';

/**
 * /game 메인 화면 (spec §1.1 PageFront). GameChrome 가 chrome spine(GameInfo + GlobalMenu +
 * MainControlBar)에 더해 맵 + 예약명령 + info 카드(general/nation/city)까지 모두 그린다. hasGeneral=false
 * 면 CharacterClaim 으로 게이트한다.
 *
 * 메인 라우트는 MyInfoLogPanel(개인기록·전투기록·전투결과·장수열전)을 children 으로 넘긴다.
 * BE API 미구현 시 빈 상태로 안내. 서브페이지(/game/*)는 각자 GameChrome 에 자기 children 을 넘긴다.
 */
export default function GameMainPage() {
    const { frontInfo } = useFrontInfo();
    const generalId = frontInfo?.general?.generalId;

    return (
        <Shell>
            <GameChrome>
                {generalId != null && <MyInfoLogPanel generalId={generalId} />}
            </GameChrome>
        </Shell>
    );
}
