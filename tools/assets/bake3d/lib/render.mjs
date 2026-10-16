// Headless Chromium host for page/render-page.mjs. three and Playwright are resolved from the
// existing web/game dependencies (three 0.171.0, @playwright/test); nothing is downloaded here.
// Pages are served from memory through request interception on a reserved .invalid origin.
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TOOL_DIR = path.resolve(HERE, '..');
export const REPO_ROOT = path.resolve(TOOL_DIR, '../../..');
const ORIGIN = 'http://bake3d.invalid';

// Software WebGL; recent Chromium needs the explicit opt-in for SwiftShader-backed WebGL.
const CHROMIUM_ARGS = ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'];

const HTML = `<!doctype html><meta charset="utf-8">
<script type="importmap">{"imports":{"three":"/three/three.module.js"}}</script>
<script type="module" src="/page/render-page.mjs"></script>`;

// Returns { threeBuildDir, chromium } or throws with the reason (callers decide skip vs fail).
export function resolveDeps() {
  const req = createRequire(path.join(REPO_ROOT, 'web/game/package.json'));
  const threeBuildDir = path.dirname(req.resolve('three'));
  const { chromium } = req('@playwright/test');
  return { threeBuildDir, chromium };
}

function within(base, rel) {
  const p = path.resolve(base, rel);
  if (p !== base && !p.startsWith(base + path.sep)) throw new Error(`path escapes ${base}`);
  return p;
}

export async function openRenderer({ executablePath } = {}) {
  const { threeBuildDir, chromium } = resolveDeps();
  const browser = await chromium.launch({
    headless: true,
    args: CHROMIUM_ARGS,
    ...(executablePath ? { executablePath } : {}),
  });
  try {
    const page = await browser.newPage();
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(String(e)));
    await page.route(`${ORIGIN}/**`, async (route) => {
      const { pathname } = new URL(route.request().url());
      let file = null;
      if (pathname === '/') return route.fulfill({ contentType: 'text/html', body: HTML });
      if (pathname.startsWith('/three/')) file = within(threeBuildDir, pathname.slice('/three/'.length));
      else if (pathname.startsWith('/page/')) file = within(path.join(TOOL_DIR, 'page'), pathname.slice('/page/'.length));
      else if (pathname.startsWith('/lib/')) file = within(path.join(TOOL_DIR, 'lib'), pathname.slice('/lib/'.length));
      if (!file) return route.fulfill({ status: 404, body: 'not found' });
      return route.fulfill({ contentType: 'text/javascript', body: await readFile(file) });
    });
    await page.goto(`${ORIGIN}/`);
    try {
      await page.waitForFunction(() => window.bake3dReady === true, null, { timeout: 30_000 });
    } catch (e) {
      throw new Error(`render page failed to start: ${pageErrors.join(' | ') || e.message}`);
    }
    const env = { browser: browser.version(), ...(await page.evaluate(() => window.bake3dEnv())) };
    return {
      env,
      async render(job) {
        const r = await page.evaluate((j) => window.bake3dRender(j), job);
        if (pageErrors.length) throw new Error(`render page error: ${pageErrors.join(' | ')}`);
        return {
          width: r.width,
          height: r.height,
          color: new Uint8Array(Buffer.from(r.color, 'base64')),
          matid: new Uint8Array(Buffer.from(r.matid, 'base64')),
        };
      },
      close: () => browser.close(),
    };
  } catch (e) {
    await browser.close();
    throw e;
  }
}
