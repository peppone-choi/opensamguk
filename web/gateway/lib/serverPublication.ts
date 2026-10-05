import { GATEWAY_API_URL, GATEWAY_UPSTREAM_TIMEOUT_MS } from "@/lib/server-api";
import { isPathServerId } from "@/lib/serverGameUrl";

/**
 * 공개 서버 목록(C8 #1357 producer, 계약판 「K5 → C8 소비 답」 · D112).
 *
 * gateway-api `GET /servers` 만 일반 목록의 원천이다.
 * - 200 은 DB 에서 확인한 PUBLIC 서버 배열이다. 확인된 0행이면 `[]` — 「열린 서버 없음」이다.
 * - VERIFYING(검증 중) 서버는 이 목록에 없다. 장수가 있는 사람에게도 숨긴다(D112 ①).
 * - 503(`SERVER_LIST_UNAVAILABLE`) · 연결 실패 · 시간 초과 · 모양이 틀린 응답은 UNKNOWN 이다.
 *   `[]` 도 PUBLIC 도 지어내지 않고, env · 구운 서버 표(lib/serverRegistry — game-api 주소를 찾는 구성원 표)로 대신하지 않는다.
 * 매 요청 새로 묻는다(no-store) — 성공한 목록도 들고 있지 않는다.
 */
export interface PublicServer {
  readonly id: string;
  readonly name: string;
  readonly generation: number | null;
  readonly gameUrl: string;
}

export type PublicServerList =
  | { readonly kind: "known"; readonly servers: readonly PublicServer[] }
  | { readonly kind: "unknown" };

const UNKNOWN: PublicServerList = { kind: "unknown" };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function publicServerOf(value: unknown): PublicServer | null {
  if (!isRecord(value)) return null;
  const { id, name, generation, gameUrl } = value;
  if (typeof id !== "string" || !isPathServerId(id)) return null;
  if (typeof name !== "string" || typeof gameUrl !== "string") return null;
  // generation 은 늘 실린다(null 이어도) — 0 을 null 로 바꾸지 않는다
  if (generation !== null && !(typeof generation === "number" && Number.isInteger(generation))) return null;
  return { id, name: name.trim() || id, generation, gameUrl };
}

/** 받은 본문 → 목록. 한 항목이라도 틀리면 UNKNOWN(일부만 보이면 공개 판단이 섞인다). 같은 id 가 둘이어도 UNKNOWN. */
export function publicServerListOf(body: unknown): PublicServerList {
  if (!Array.isArray(body)) return UNKNOWN;
  const servers: PublicServer[] = [];
  const seen = new Set<string>();
  for (const item of body) {
    const server = publicServerOf(item);
    if (!server || seen.has(server.id)) return UNKNOWN;
    seen.add(server.id);
    servers.push(server);
  }
  return { kind: "known", servers };
}

export async function readPublicServers(): Promise<PublicServerList> {
  try {
    const response = await fetch(`${GATEWAY_API_URL.replace(/\/+$/, "")}/servers`, {
      cache: "no-store",
      signal: AbortSignal.timeout(GATEWAY_UPSTREAM_TIMEOUT_MS),
    });
    if (!response.ok) return UNKNOWN;
    return publicServerListOf(await response.json());
  } catch {
    return UNKNOWN;
  }
}
