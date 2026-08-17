// OPENSAM-196 — 턴 완료 신호 대기소.
//
// 지금까지 Shell은 턴 SSE를 받으면 `window.location.reload()`로 페이지를 통째로 다시 로드했다.
// 턴마다 스크롤 위치, 입력 중이던 폼, 열려 있던 모달이 전부 날아간다. 화면이 필요한 데이터만
// 다시 읽으면 그 손실이 없다.
//
// [commandResultEvents]와 같은 모양이다: 연결은 이미 앱 전역에 떠 있는 Shell의 SSE 하나
// (`hooks/useSSE.ts`)가 갖고, 여기는 그 신호를 구독자에게 나눠 주기만 한다. 화면마다 EventSource를
// 새로 열면 탭당 상시 연결이 화면 수만큼 늘고 오리진당 연결 한도를 갉아먹는다.
//
// Shell 밖에서 렌더되는 화면에는 신호가 오지 않는다 — 갱신이 늦어질 뿐 틀리지 않는다.

type Listener = () => void;

const listeners = new Set<Listener>();

/** Shell의 SSE 연결이 `turnCompleted` 프레임을 받을 때 호출한다. */
export function deliverTurnCompleted() {
    // 콜백이 자기 자신을 해제할 수 있으므로 복사본을 돈다.
    for (const listener of [...listeners]) listener();
}

/**
 * 턴 완료 신호를 구독한다. 반환값은 해제 함수이며 언마운트 시 **반드시** 불러야 한다 —
 * 안 부르면 화면을 옮길 때마다 리스너가 쌓여 턴 한 번에 죽은 화면의 요청이 그만큼 나간다.
 */
export function subscribeTurnCompleted(listener: Listener): () => void {
    listeners.add(listener);
    return () => {
        listeners.delete(listener);
    };
}

/** 테스트 전용 — 남은 구독을 모두 버린다. */
export function __resetTurnListeners() {
    listeners.clear();
}
