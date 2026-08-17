// OPENSAM-45 (V2-1① 1-b·1-c·1-e) — 명령 결과 깨움 신호 대기소.
//
// 지금까지 FE는 제출 뒤 300ms 간격으로 최대 20번 결과를 되물었다(최악 6초, 평균적으로도 늘 한 박자
// 늦다). 엔진은 flush commit 뒤 outbox 릴레이에서 `commandSettled`를 발행하므로, 그 신호를 받아
// **즉시** 한 번 읽으면 폴링 지연이 사라진다.
//
// **신호는 결과가 아니다.** 정본은 여전히 `GET /api/command/result/{requestId}`이고, 이 모듈은
// "이제 읽어도 된다"만 알려 준다. 신호가 유실돼도(SSE 끊김·프록시 버퍼링) 기존 폴링이 그대로 살아
// 있으므로 결과를 잃지 않는다 — 폴링을 대체하는 게 아니라 앞당긴다.
//
// **연결을 직접 열지 않는다.** 여기서 EventSource를 열면 (a) 첫 제출은 구독이 붙기 전에 발행된
// 자기 신호를 놓치고(Redis pub/sub은 replay가 없다), (b) 탭마다 Shell의 연결 위에 두 번째 상시
// 연결이 얹혀 오리진당 연결 한도를 갉아먹는다. 그래서 이미 앱 전역에 떠 있는 Shell의 SSE
// (`hooks/useSSE.ts`)가 신호를 받아 [deliverCommandSettled]로 넘겨준다. Shell 밖 화면에서는
// 신호가 오지 않고 폴링이 예전 속도로 결론을 낸다 — 느려질 뿐 틀리지 않는다.

export const COMMAND_SETTLED_EVENT = 'commandSettled';

type Waiter = (woken: boolean) => void;

const waiters = new Map<string, Set<Waiter>>();

/** Shell의 SSE 연결이 `commandSettled` 프레임을 받을 때 호출한다. */
export function deliverCommandSettled(raw: string) {
    let requestId: string | undefined;
    try {
        requestId = (JSON.parse(raw) as { requestId?: string }).requestId;
    } catch {
        return;
    }
    if (!requestId) return;
    const pending = waiters.get(requestId);
    if (!pending) return;
    // 콜백이 자기 자신을 해제하므로 복사본을 돈다.
    for (const waiter of [...pending]) waiter(true);
}

/**
 * `requestId`의 깨움 신호를 기다린다. 신호가 오지 않으면 [timeoutMs] 뒤 `false`로 끝난다(거부하지
 * 않는다). 반환값은 판정이 아니라 "지금 정본을 읽어라"라는 뜻일 뿐이다.
 */
export function waitForCommandSettled(requestId: string, timeoutMs: number): Promise<boolean> {
    return new Promise(resolve => {
        const settle = (woken: boolean) => {
            clearTimeout(timer);
            const pending = waiters.get(requestId);
            pending?.delete(waiter);
            if (pending && pending.size === 0) waiters.delete(requestId);
            resolve(woken);
        };
        const waiter: Waiter = woken => settle(woken);
        const timer = setTimeout(() => settle(false), timeoutMs);

        const pending = waiters.get(requestId) ?? new Set<Waiter>();
        pending.add(waiter);
        waiters.set(requestId, pending);
    });
}

/** 테스트 전용 — 남은 대기자를 타이머까지 정리하며 깨움 없이 끝낸다. */
export function __resetCommandSettledWaiters() {
    for (const pending of [...waiters.values()]) for (const waiter of [...pending]) waiter(false);
    waiters.clear();
}
