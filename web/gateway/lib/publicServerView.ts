import { readPublicServers, type PublicServer } from "@/lib/serverPublication";

/**
 * 로그인 · 로비 · 가입이 서버 렌더에서 쓰는 공개 서버 보기(D105 계층: 화면 → 보기 모델 → 원천 읽기).
 * 원천을 모르면(UNKNOWN) 빈 목록 + `registry: 'error'` — 화면은 「서버 목록을 확인하지 못했습니다」를 보이고
 * 「열린 서버 없음」(확인된 빈 목록)과 가른다. env · 구운 서버 표로 대신하지 않는다.
 */
export interface PublicServerView {
  readonly servers: readonly PublicServer[];
  readonly registry: "ok" | "error";
}

export async function publicServerView(): Promise<PublicServerView> {
  const list = await readPublicServers();
  return list.kind === "known" ? { servers: list.servers, registry: "ok" } : { servers: [], registry: "error" };
}
