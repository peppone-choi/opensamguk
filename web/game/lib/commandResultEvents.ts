// OPENSAM-45 (V2-1① 1-b·1-c·1-e) — 명령 결과 깨움 신호 대기소.
//
// 지금까지 FE는 제출 뒤 300ms 간격으로 최대 20번 결과를 되물었다(최악 6초, 평균적으로도 늘 한 박자
// 늦다). 엔진은 flush commit 뒤 outbox 릴레이에서 `commandSettled`를 발행하므로, 그 신호를 받아
// **즉시** 한 번 읽으면 폴링 지연이 사라진다.
//
// **신호는 결과가 아니다. 식별자조차 없다.** payload는 `at` 하나뿐이라(`CommandSettledEvent` 참고)
// 신호의 뜻은 "누군가의 명령 결과가 커밋됐다"까지다. 기다리는 쪽이 **자기** requestId로 정본
// `GET /api/command/result/{requestId}`를 한 번 되읽어 확인한다. 남의 명령이 만든 신호에 깨어나
// 헛읽는 비용은 폴링 19번보다 싸고, 그 대가로 월드 전역 채널에 아무 정보도 흐르지 않는다.
//
// 신호가 유실돼도(SSE 끊김·프록시 버퍼링) 기존 폴링이 그대로 살아 있으므로 결과를 잃지 않는다 —
// 폴링을 대체하는 게 아니라 앞당긴다.
//
// **연결을 직접 열지 않는다.** 여기서 EventSource를 열면 (a) 첫 제출은 구독이 붙기 전에 발행된
// 신호를 놓치고(Redis pub/sub은 replay가 없다), (b) 탭마다 Shell의 연결 위에 두 번째 상시 연결이
// 얹혀 오리진당 연결 한도를 갉아먹는다. 그래서 이미 앱 전역에 떠 있는 Shell의 SSE
// (`hooks/useSSE.ts`)가 신호를 받아 [deliverCommandSettled]로 넘겨준다. Shell 밖 화면에서는 신호가
// 오지 않고 폴링이 예전 속도로 결론을 낸다 — 느려질 뿐 틀리지 않는다.

export const COMMAND_SETTLED_EVENT = 'commandSettled';

type Listener = () => void;

const listeners = new Set<Listener>();

/** Shell의 SSE 연결이 `commandSettled` 프레임을 받을 때 호출한다. */
export function deliverCommandSettled() {
    // 콜백이 자기 자신을 해제할 수 있으므로 복사본을 돈다.
    for (const listener of [...listeners]) listener();
}

/**
 * 깨움 신호를 구독한다. 반환값은 해제 함수이며, 기다림이 끝나면 **반드시** 불러야 한다 —
 * 안 부르면 제출할 때마다 리스너가 쌓여 신호 한 번에 죽은 요청이 그만큼 나간다.
 */
export function subscribeCommandSettled(listener: Listener): () => void {
    listeners.add(listener);
    return () => {
        listeners.delete(listener);
    };
}

/** 테스트 전용 — 남은 구독을 모두 버린다. */
export function __resetCommandSettledListeners() {
    listeners.clear();
}
