import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';

/**
 * 목 화면용 지형 서빙 — **개발 전용**.
 *
 * 실제로는 game-api 의 `GET /api/map/terrain` 이 저장소의 `data/map/han-tiles.json` 을 내보낸다.
 * 그 경로(`/api/game/**`)는 게이트웨이로 프록시되므로 백엔드를 띄우지 않으면 500 이 된다. 목
 * 화면이 실제 지형을 그리도록 같은 파일을 프록시 밖 경로에서 그대로 내보낸다.
 *
 * 프로덕션에서는 절대 쓰지 않는다 — `NODE_ENV === 'production'` 이면 404 다.
 */
export async function GET() {
    if (process.env.NODE_ENV === 'production') {
        return new Response('not found', { status: 404 });
    }
    // web/game → 저장소 루트는 두 단계 위다.
    const file = path.join(process.cwd(), '..', '..', 'data', 'map', 'han-tiles.json');
    try {
        const bytes = await readFile(file);
        const etag = `"${createHash('sha256').update(bytes).digest('hex')}"`;
        return new Response(bytes, {
            headers: {
                'content-type': 'application/json; charset=utf-8',
                etag,
                'cache-control': 'no-store',
            },
        });
    } catch (cause) {
        return new Response(
            JSON.stringify({ error: `han-tiles.json 을 읽지 못했다: ${file}`, cause: String(cause) }),
            { status: 500, headers: { 'content-type': 'application/json; charset=utf-8' } },
        );
    }
}
