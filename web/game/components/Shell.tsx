// 옛 게임 셸 자리. 머리줄 · 메뉴 · 하단 탭은 이제 /game 레이아웃의 GameFrame 하나가 그린다(v3.1 셸 통합).
// 옛 화면들이 아직 <Shell> 로 감싸고 있어 이름만 남긴다 — 화면을 지우거나 새로 쓸 때 import 와 시험의 vi.mock 을 같이 걷는다.
// 새로 들여오면 __tests__/legacy-shell-importers.test.ts 가 빨갛다. 들여오는 곳이 0 이 되면 이 파일과 그 가드를 지운다(K3).
export default function Shell({ children }: { children: React.ReactNode }) {
    return <>{children}</>;
}
