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
 * `/api/game/**` 는 dev/prod 모두 이 route 하나로 온다 — web/game은 더 이상 자체 프록시를 갖지
 * 않는다(#516 §5 통합, 이 커밋).
 *
 * 이전에 web/game이 동형 route(`app/api/game/[...path]/route.ts`)를 따로 갖고 있었을 때 실제 발산이
 * 하나 있었다: web/gateway는 원래 GET/POST/PATCH/DELETE를 export했지만 web/game은 GET/POST뿐이었다.
 * `web/game/lib/api.ts`의 PATCH 헬퍼(`patchGameSettings`)는 `app/game/admin1/page.tsx`(관리자
 * 게임설정 저장 버튼)의 살아 있는 호출자였고, nginx 없이 `web/game`을 `pnpm dev`로 단독 실행하는
 * 프론트 dev 흐름에서 그 요청이 web/game 자신의 route로 갔기 때문에 PATCH export가 없어 405가 났다
 * (#516 리뷰 F1 — dev에서만 깨지고 이 route가 서빙하는 경로는 항상 정상이었다).
 *
 * 근본 수정은 증상(PATCH export 추가)이 아니라 프록시를 하나로 합치는 것이었다: web/game의 route와
 * 그 전용 `lib/serverRegistry.ts`를 삭제하고, `web/game/next.config.mjs`의 `rewrites()`가
 * `/api/game/:path*`를 이 route로 넘긴다(docker/prod에서는 nginx `location /api/game/`가 이미 여기로
 * 보낸다 — `infra/nginx/nginx.conf`). PATCH/DELETE·`duplex`·서버선택 로직 차이는 발산이 남을 자리
 * 자체가 없어지면서 전부 함께 소멸했다.
 *
 * - 401/403 모두 그대로 전파, SSE 경로도 동일 — `!upstream.ok`는 상태코드를 특별취급하지 않는다(이
 *   파일 테스트; #516 리뷰 F3 — 이전엔 401만 검증되어 403이 SSE에서 뭉개져도 잡지 못했다).
 * - sam_refresh로 서버사이드 재시도 없음 — 그 쿠키는 `path=/api/auth`로 좁혀 심어져 여기서도 읽을 수
 *   없다(web/gateway/lib/cookies.ts 참고). 401 복구는 클라이언트가 `/api/auth/me`를 거친다 — 의도된
 *   설계, 결함 아님.
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
