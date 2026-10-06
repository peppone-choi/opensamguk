// e2e 용 gateway-api 공개 서버 목록 스텁(C8 #1357 `GET /servers` 모양) — playwright globalSetup 이 띄운다.
//
// 스모크는 백엔드 없이 `next start` 만 띄운다. 로그인 · 로비 · 가입은 공개 목록을 **서버 렌더에서** gateway-api 에 묻는데
// (lib/serverPublication), 그 요청은 page.route 로 가로챌 수 없다. 그래서 게이트웨이가 기본으로 묻는 주소
// (GATEWAY_API_URL 이 없으면 http://localhost:8080)에 `/servers` 만 답하는 스텁을 둔다.
// - 기본 목록은 SERVER_REGISTRY_JSON(CI 스모크 env: pep · uni)에서 만든다 — 지금까지의 스모크 장면 그대로.
// - 시험은 `/__stub/servers` 로 모드를 바꿀 수 있다(e2e/support/publicServers.ts) — 원천 불명(503) · 검증 중 숨김 장면.
// - 그 밖의 경로는 연결을 끊는다 — 스텁이 없을 때와 같은 「서버 없음」으로 남는다(다른 서버 쪽 호출의 동작을 바꾸지 않는다).
// 포트가 이미 쓰이면(개발자의 실제 gateway-api) 띄우지 않고 그것을 쓴다.
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export const STUB_PORT = Number(process.env.E2E_GATEWAY_API_STUB_PORT ?? 8080);

type Mode = { readonly kind: 'list'; readonly servers: readonly unknown[] } | { readonly kind: 'unavailable' };

function registryServers(): readonly unknown[] {
  const raw = process.env.SERVER_REGISTRY_JSON?.trim()
    || JSON.stringify((JSON.parse(readFileSync(join(__dirname, '..', '..', 'config', 'servers.json'), 'utf8')) as { servers: unknown[] }).servers);
  const entries = JSON.parse(raw) as { id: string; name?: string; generation?: number }[];
  return entries.map((entry) => ({ id: entry.id, name: entry.name ?? entry.id, generation: entry.generation ?? null, gameUrl: `/game/${entry.id}` }));
}

function body(req: IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    let text = '';
    req.on('data', (chunk) => { text += String(chunk); });
    req.on('end', () => resolve(text));
  });
}

function send(res: ServerResponse, status: number, payload: unknown): void {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(payload));
}

export default async function globalSetup(): Promise<(() => Promise<void>) | undefined> {
  const initial: Mode = { kind: 'list', servers: registryServers() };
  let mode: Mode = initial;
  const server: Server = createServer((req, res) => {
    const path = new URL(req.url ?? '/', 'http://stub').pathname;
    if (req.method === 'GET' && path === '/servers') {
      if (mode.kind === 'unavailable') send(res, 503, { error: { code: 'SERVER_LIST_UNAVAILABLE' } });
      else send(res, 200, mode.servers);
      return;
    }
    if (req.method === 'POST' && path === '/__stub/servers') {
      void body(req).then((text) => {
        const next = JSON.parse(text || '{}') as { mode?: string; servers?: unknown[] };
        mode = next.mode === 'unavailable' ? { kind: 'unavailable' } : next.mode === 'list' && next.servers ? { kind: 'list', servers: next.servers } : initial;
        send(res, 200, { ok: true });
      });
      return;
    }
    req.socket.destroy();
  });
  const started = await new Promise<boolean>((resolve) => {
    server.once('error', (error: NodeJS.ErrnoException) => {
      console.warn(`[e2e] gateway-api 스텁을 띄우지 못했다(${error.code}) — :${STUB_PORT} 의 기존 서버를 쓴다`);
      resolve(false);
    });
    server.listen(STUB_PORT, () => resolve(true));
  });
  if (!started) return undefined;
  return () => new Promise<void>((resolve) => server.close(() => resolve()));
}
