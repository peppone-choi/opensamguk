// 옛 게임 셸 자리. 머리줄 · 메뉴 · 하단 탭은 이제 /game 레이아웃의 GameFrame 하나가 그린다(v3.1 셸 통합).
// 옛 화면들이 아직 <Shell> 로 감싸고 있어 이름만 남긴다 — 화면을 새로 만들 때 이 감싸기를 걷는다.
export default function Shell({ children }: { children: React.ReactNode }) {
    return <>{children}</>;
}
