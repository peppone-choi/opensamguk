import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { ACCESS_COOKIE } from "@/lib/cookies";
import {
  getServers,
  isValidEmptyServerRegistry,
  resolveGameApiOrigin,
} from "@/lib/serverRegistry";
import { isPathServerId } from "@/lib/serverGameUrl";

/**
 * 프로덕션에서 `/api/game/**` 는 이 route로 온다(#516) — `web/game`의 동형 route는
 * dev/호환용 nginx(`infra/nginx/default.conf`)에서만 도달하고, 프로덕션 제어면
 * (`opensamguk-docker` `infra/nginx/nginx.conf`)에는 `/api/game/` location이 없어
 * `/api/` catch-all이 잡아 여기로 온다. 두 route는 프록시 로직을 중복 보유한다 — 발산 지점과
 * 판정(#516 조사, 2026-08-24):
 *
 * - 401 그대로 전파, SSE 401-우선-확인(#514) 동작 동일 — 검증됨(이 파일 테스트; 403 등 다른
 *   에러 등급은 양쪽 다 테스트가 없다 — 이 커밋이 만든 구멍이 아니라 기존 구멍).
 * - sam_refresh로 서버사이드 재시도 없음 — 동일. 그 쿠키는 `path=/api/auth`로 좁혀 심어져
 *   여기서도 읽을 수 없다(web/gateway/lib/cookies.ts 참고). 401 복구는 클라이언트가
 *   `/api/auth/me`를 거친다 — 의도된 설계, 결함 아님.
 * - HTTP 메서드: 이쪽은 GET/POST/PATCH/DELETE, `web/game` 쪽은 원래 GET/POST뿐이었다.
 *   `web/game/lib/api.ts:803`의 PATCH 헬퍼(`patchGameSettings`)는 무호출이 아니라
 *   `app/game/admin1/page.tsx:59`(관리자 게임설정 저장 버튼)의 살아 있는 호출자였다 — 이
 *   요청은 `BASE='/api/game'`를 거쳐 `/api/game/api/admin/game-settings`로 나가고, dev nginx
 *   (`infra/nginx/default.conf:156`)는 prefix 길이로 `/api/game/`을 `web/game` route로 보낸다.
 *   그 route에 PATCH export가 없어 Next App Router가 405를 돌려준다 — dev에서만 깨지고
 *   prod(이 route)는 정상인 실제 발산이었다(#516 리뷰 F1). `web/game`에도 PATCH/DELETE export를
 *   추가해 닫았다(대칭을 위해 DELETE도 추가 — 현재 호출자는 없지만 이 route에도 없다).
 * - fetch init에 `duplex: 'half'`가 없다(`web/game`엔 있음) — 여기서도 body는 항상
 *   `req.text()`로 먼저 버퍼링한 문자열이라(스트리밍 아님) Node fetch가 duplex를 요구하지
 *   않는다. 결함 아님.
 * - 서버 선택 로직(`resolveSelectedGameApiOrigin` vs `resolveGameApiUrl`)이 다른 것은
 *   두 앱이 서로 다른 `serverRegistry` 구현(멀티서버 게이트웨이 vs 단일 게임서버)을 쓰기
 *   때문 — 의도된 차이.
 *
 * 두 route를 한 벌로 합칠지는 별도 결정 필요(#516 §5) — 이 커밋은 단기 완화(§6: 프로덕션
 * 경로에 401 회귀 테스트 추가)만 다룬다.
 */
const SERVER_COOKIE = "sam_server";

export const dynamic = "force-dynamic";

const encoder = new TextEncoder();
const SSE_HEARTBEAT_MS = 25_000;

function isEventStream(contentType: string): boolean {
  return contentType.includes("text/event-stream");
}

function isTurnSsePath(path: string[]): boolean {
  return path.join("/") === "sse/turn";
}

function defaultGameApiOrigin(): string | undefined {
  const defaultServerId = getServers()[0]?.id;
  return defaultServerId
    ? resolveGameApiOrigin(defaultServerId)
    : compatibilityGameApiOrigin();
}

function compatibilityGameApiOrigin(): string | undefined {
  return isValidEmptyServerRegistry() ? process.env.GAME_API_ORIGIN : undefined;
}

function configuredServerId(): string | undefined {
  const serverId = process.env.SERVER_ID;
  return serverId && isPathServerId(serverId) ? serverId : undefined;
}

function resolveSelectedGameApiOrigin(
  serverId: string | undefined,
): string | undefined {
  if (serverId === undefined) return defaultGameApiOrigin();
  if (!isPathServerId(serverId)) return undefined;
  const registryOrigin = resolveGameApiOrigin(serverId);
  if (registryOrigin) return registryOrigin;
  return serverId === configuredServerId()
    ? compatibilityGameApiOrigin()
    : undefined;
}

function sseHeaders(
  contentType = "text/event-stream;charset=UTF-8",
): HeadersInit {
  return {
    "Content-Type": contentType,
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  };
}

async function streamEventSource(
  target: string,
  init: RequestInit,
): Promise<NextResponse> {
  const upstreamAbort = new AbortController();
  // (A) 연결 수립 시점 실패: fetch를 ReadableStream 밖에서 먼저 await한다 — 아직 아무것도
  // 스트리밍하지 않았으므로 upstream status를 그대로(JSON 경로와 동일 계약) 반환할 수 있다.
  const upstream = await fetch(target, {
    ...init,
    signal: upstreamAbort.signal,
  });
  if (!upstream.ok) {
    const contentType = upstream.headers.get("content-type") ?? "application/json";
    const body = await upstream.text();
    return new NextResponse(body, {
      status: upstream.status,
      headers: { "Content-Type": contentType },
    });
  }

  let heartbeat: ReturnType<typeof setInterval> | null = null;
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  let closed = false;

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const send = (text: string) => {
        if (!closed) controller.enqueue(encoder.encode(text));
      };
      const close = () => {
        if (closed) return;
        closed = true;
        if (heartbeat) clearInterval(heartbeat);
        controller.close();
      };

      send(": proxy-connected\n\n");
      heartbeat = setInterval(() => send(": proxy-hb\n\n"), SSE_HEARTBEAT_MS);

      void (async () => {
        try {
          reader = upstream.body?.getReader() ?? null;
          if (!reader) return;
          while (!closed) {
            const chunk = await reader.read();
            if (chunk.done) break;
            if (!closed && chunk.value) controller.enqueue(chunk.value);
          }
        } catch {
          // (B) 이미 열린 스트림 중간의 실패 — status는 이미 200으로 나갔으므로 되돌릴 수
          // 없다. 이벤트로만 알릴 수 있다(#514 범위 밖, payload 설계는 별도 결정 필요).
          send("event: error\ndata: {}\n\n");
        } finally {
          close();
        }
      })();
    },
    cancel() {
      closed = true;
      if (heartbeat) clearInterval(heartbeat);
      void reader?.cancel();
      upstreamAbort.abort();
    },
  });

  return new NextResponse(stream, { status: 200, headers: sseHeaders() });
}

async function forward(
  req: NextRequest,
  path: string[],
): Promise<NextResponse> {
  const store = await cookies();
  const access = store.get(ACCESS_COOKIE)?.value;
  const serverId =
    req.nextUrl.searchParams.get("server") ?? store.get(SERVER_COOKIE)?.value;
  const base = resolveSelectedGameApiOrigin(serverId)?.replace(/\/+$/, "");
  if (!base)
    return NextResponse.json(
      { error: "게임 서버를 찾을 수 없습니다." },
      { status: 503 },
    );

  const searchParams = new URLSearchParams(req.nextUrl.searchParams);
  searchParams.delete("server");
  const search = searchParams.toString();
  const target = `${base}/${path.join("/")}${search ? `?${search}` : ""}`;
  const headers: Record<string, string> = {};
  if (access) headers.Authorization = `Bearer ${access}`;

  const init: RequestInit = {
    method: req.method,
    headers,
    cache: "no-store",
  };

  if (req.method !== "GET" && req.method !== "HEAD") {
    const contentType = req.headers.get("content-type");
    if (contentType) headers["Content-Type"] = contentType;
    init.body = await req.text();
  }

  if (req.method === "GET" && isTurnSsePath(path)) {
    return streamEventSource(target, init);
  }

  const upstream = await fetch(target, init);
  const contentType =
    upstream.headers.get("content-type") ?? "application/json";

  if (isEventStream(contentType)) {
    return new NextResponse(upstream.body, {
      status: upstream.status,
      headers: sseHeaders(contentType),
    });
  }

  const body = await upstream.text();
  return new NextResponse(body, {
    status: upstream.status,
    headers: {
      "Content-Type": contentType,
    },
  });
}

export async function GET(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  return forward(req, path);
}

export async function POST(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  return forward(req, path);
}

export async function PATCH(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  return forward(req, path);
}

export async function DELETE(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  return forward(req, path);
}
