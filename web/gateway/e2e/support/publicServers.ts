// 시험에서 gateway-api 스텁(e2e/support/gatewayApiStub.ts)의 공개 목록을 바꾼다. 시험이 끝나면 resetPublicServers 로 되돌린다.
import { STUB_PORT } from './gatewayApiStub';

export interface StubPublicServer {
  readonly id: string;
  readonly name: string;
  readonly generation: number | null;
  readonly gameUrl: string;
}

async function post(payload: unknown): Promise<void> {
  const response = await fetch(`http://127.0.0.1:${STUB_PORT}/__stub/servers`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!response.ok) throw new Error(`gateway-api 스텁 모드 변경 실패: ${response.status}`);
}

/** 공개 목록을 이것으로(검증 중 서버를 뺀 목록 등). */
export const setPublicServers = (servers: readonly StubPublicServer[]) => post({ mode: 'list', servers });
/** 원천 불명 — `/servers` 가 503 SERVER_LIST_UNAVAILABLE. */
export const makePublicServersUnavailable = () => post({ mode: 'unavailable' });
/** 처음 목록(SERVER_REGISTRY_JSON 에서 만든 것)으로. */
export const resetPublicServers = () => post({ mode: 'reset' });
