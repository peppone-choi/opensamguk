'use client';

import Shell from '../../components/Shell';
import GameChrome from '../../components/game/GameChrome';

/**
 * /game 메인 화면 (spec §1.1 PageFront). GameChrome 가 chrome spine(GameInfo + GlobalMenu +
 * MainControlBar)에 더해 맵 + 예약명령 + info 카드(general/nation/city)까지 모두 그린다. hasGeneral=false
 * 면 CharacterClaim 으로 게이트한다.
 *
 * de-dup: 과거 이 페이지의 MyPageContent 가 general/nation/city 카드를 한 번 더 그려 GameChrome 의
 * 카드와 화면에 "내 정보" 블록이 중복으로 나타났다. 이제 카드는 GameChrome 에서만 렌더하므로 메인
 * 라우트는 children 을 비운다(GameChrome 의 .ib-content 는 :empty 면 숨겨짐). 기존 /game/* 서브페이지는
 * 각자 GameChrome 에 자기 children 을 넘겨 그대로 동작한다(영향 없음).
 */
export default function GameMainPage() {
    return (
        <Shell>
            <GameChrome />
        </Shell>
    );
}
