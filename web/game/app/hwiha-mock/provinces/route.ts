import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';

/**
 * 목 화면용 프로빈스 식별 PNG 서빙 — **개발 전용**.
 *
 * 칸마다 프로빈스(구역)·군국 번호가 무손실로 들어 있는 그림이다. 전장의 안개가 「군국 단위로
 * 가린다」를 하려면 칸이 어느 군국인지 알아야 하고, 그 답이 이 그림이다.
 *
 * 실제로는 game-api `GET /api/map/provinces` 가 내보내지만 그 경로는 게이트웨이로 프록시된다.
 * 파일은 `python3 tools/map/build_province_map.py --input data/map/han-tiles.json
 * --output-dir data/map --map-code han-world-v3` 로 만든다.
 */
export async function GET() {
    if (process.env.NODE_ENV === 'production') {
        return new Response('not found', { status: 404 });
    }
    const file = path.join(process.cwd(), '..', '..', 'data', 'map', 'han-world-v3-provinces.png');
    try {
        const bytes = await readFile(file);
        const etag = `"sha256-${createHash('sha256').update(bytes).digest('hex')}"`;
        return new Response(bytes, {
            headers: { 'content-type': 'image/png', etag, 'cache-control': 'no-store' },
        });
    } catch (cause) {
        return new Response(`프로빈스 그림을 읽지 못했다: ${file} (${String(cause)})`, { status: 500 });
    }
}
