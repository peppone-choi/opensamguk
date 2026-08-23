import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { ACCESS_COOKIE } from "@/lib/cookies";
import {
  getServers,
  isValidEmptyServerRegistry,
  resolveGameApiOrigin,
} from "@/lib/serverRegistry";
import { isPathServerId } from "@/lib/serverGameUrl";

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
