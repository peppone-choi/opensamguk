// Vitest 전역 셋업 — jest-dom 매처(toBeInTheDocument 등) 등록 + jsdom 에 없는
// 브라우저 API(ResizeObserver / PointerEvent / setPointerCapture) 폴리필.
// MapViewer 는 ResizeObserver 와 Pointer 이벤트를 직접 쓰므로 jsdom 기본값만으로는 마운트되지 않는다.
import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// 각 테스트 후 DOM/모킹 정리(테스트 간 누수 방지).
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

// jsdom 은 ResizeObserver 가 없다 — MapViewer 의 캔버스 폭 추적용 stub.
class ResizeObserverStub {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
}
if (!('ResizeObserver' in globalThis)) {
    (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub;
}

// jsdom 의 PointerEvent 는 button/clientX 등 일부 필드를 무시 → 테스트가 좌표를 직접
// 넘길 수 있도록 MouseEvent 기반 최소 PointerEvent 를 정의한다.
if (!('PointerEvent' in globalThis)) {
    class PointerEventPolyfill extends MouseEvent {
        pointerId: number;
        constructor(type: string, params: PointerEventInit = {}) {
            super(type, params);
            this.pointerId = params.pointerId ?? 1;
        }
    }
    (globalThis as unknown as { PointerEvent: unknown }).PointerEvent = PointerEventPolyfill;
}

// setPointerCapture/releasePointerCapture 는 jsdom 에 없다 — 팬(드래그) 캡처 호출이 throw 하지 않도록 stub.
if (!HTMLElement.prototype.setPointerCapture) {
    HTMLElement.prototype.setPointerCapture = function setPointerCapture(): void {};
}
if (!HTMLElement.prototype.releasePointerCapture) {
    HTMLElement.prototype.releasePointerCapture = function releasePointerCapture(): void {};
}
