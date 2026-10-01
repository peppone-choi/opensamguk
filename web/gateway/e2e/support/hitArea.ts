// 게이트웨이 스모크 공용 — 누를 영역을 「가운데에서 훑은 elementFromPoint 적중 범위」로 잰다(K10 board-lint 과 같은 뜻).
// parity.smallTouchTargets 는 상자 크기만 잰다 — 지도 위에 떠 있는 패널은 겹침까지 봐야 해서 따로 둔다.
import type { Page } from '@playwright/test';

/** 누를 영역(가운데에서 훑은 elementFromPoint 적중 범위, K10 board-lint 과 같은 뜻)이 44 미만이거나 가운데가 덮인 것.
 *  parity.smallTouchTargets 는 상자 크기를 잰다 — 지도 위에 떠 있는 패널은 겹침까지 봐야 해서 적중 범위로 잰다. */
export async function smallHitAreas(page: Page, root: string): Promise<string[]> {
  // 화면 밖(아래)에 있는 것은 elementFromPoint 가 못 본다 — 한 화면씩 내려가며 위아래가 화면 안에 다 든 것만 한 번씩 잰다.
  // 가운데만 보고 재면 화면 아래 끝에 걸친 줄이 잘린 만큼 작게 잡힌다(10-02 K5 계정 모바일: 탈퇴 줄 44 → 29).
  // 한 화면보다 큰 것 · 끝까지 다 들지 못한 것은 마지막 화면에서 가운데가 보이면 잰다.
  return page.locator(root).first().evaluate(async (node) => {
    const out: string[] = [];
    const seen = new Set<Element>();
    const targets = Array.from(node.querySelectorAll<HTMLElement>('button, a[href], input:not([type="hidden"])'));
    const step = Math.max(200, Math.floor(innerHeight * 0.8));
    for (let y = 0; ; y += step) {
      window.scrollTo(0, y);
      await new Promise((resolve) => requestAnimationFrame(() => resolve(null)));
      const last = y + innerHeight >= document.documentElement.scrollHeight;
      for (const el of targets) {
        if (seen.has(el)) continue;
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) { seen.add(el); continue; }
        const cx = r.left + r.width / 2;
        const cy = r.top + r.height / 2;
        if (cy < 0 || cy >= innerHeight || cx < 0 || cx >= innerWidth) continue;
        if (!last && (r.top < 0 || r.bottom > innerHeight)) continue;
        seen.add(el);
        const mine = (x: number, yy: number) => {
          if (x < 0 || yy < 0 || x >= innerWidth || yy >= innerHeight) return false;
          const hit = document.elementFromPoint(x, yy);
          return !!hit && (hit === el || el.contains(hit));
        };
        const name = (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 20);
        if (!mine(cx, cy)) {
          // 가운데가 다른 요소에 덮였다 — 이 화면엔 열린 층(대화상자 · 시트)이 없으니 결함이다(K10 board-lint 「덮인 누를 것」).
          const top = document.elementFromPoint(cx, cy);
          out.push(`덮임 ${el.tagName.toLowerCase()} "${name}" ← ${top ? top.className || top.tagName : '없음'}`);
          continue;
        }
        const reach = (dx: number, dy: number) => { let d = 0; while (d < 64 && mine(cx + dx * (d + 1), cy + dy * (d + 1))) d += 1; return d; };
        const w = reach(-1, 0) + reach(1, 0) + 1;
        const h = reach(0, -1) + reach(0, 1) + 1;
        if (w < 44 || h < 44) out.push(`${el.tagName.toLowerCase()} "${name}" ${w}×${h}`);
      }
      if (last) break;
    }
    window.scrollTo(0, 0);
    const missed = targets.filter((el) => !seen.has(el)).map((el) => `못 잼 ${el.tagName.toLowerCase()} "${(el.textContent ?? '').trim().slice(0, 20)}"`);
    return [...out, ...missed];
  });
}

