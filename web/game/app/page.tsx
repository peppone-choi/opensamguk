import { redirect } from 'next/navigation';

// 게임 앱 뿌리(`/`)는 운영에서 게이트웨이가 가져가 닿지 않는다(로컬 web-game 직접 접속 때만 열린다).
// 옛 허브(삼모 메뉴 타일)는 지웠다(K9) — 작전실 입구(/game)로 보낸다.
export default function Home(): never {
    redirect('/game');
}
