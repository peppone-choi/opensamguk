// 두 앱 e2e 공용 — 「누를 영역 44」의 뜻 하나(K0 2026-10-02): 누를 것의 가운데에서 바깥으로 훑은 elementFromPoint 적중 범위.
// 상자 크기가 아니다. 패딩 · ::before 로 넓힌 단추는 넓힌 만큼 누를 수 있고, 겹친 상자가 가린 만큼은 누를 수 없다.
// K5 게이트웨이 도우미(e2e/support/hitArea.ts, b7b207b8)를 옮겨 왔다 — 한 화면씩 내려가며 맞혀 보고, 가로로 미는 줄은 들여 재고,
// 붙박인 층(떠 있는 단추 · 아래 탭)에 덮이면 다음 화면에서 다시 잰다. 여기에 셋을 더했다.
// - 라벨로 감싸거나 for 로 이은 입력(체크 상자 · 라디오)은 입력과 라벨 중 누를 영역 넓이가 큰 쪽 하나로 잰다(K0 · K5 서버 탭 20×20).
//   너비 · 높이를 따로 골라 섞지 않는다(섞으면 둘 다 44×44 가 아닌데 통과한다).
// - 창 스크롤로 못 본 것(안쪽 세로 스크롤 상자 등)은 마지막에 그 요소를 들여 한 번 더 잰다. 그래도 못 재면 「못 잼」이다.
// - 잰 뒤 창 · 들인 스크롤 상자의 위치를 되돌린다.
//
// Playwright 를 import 하지 않는다(@opensamguk/ui exports 는 src 만이라 이 파일은 패키지에 실리지 않는다).
// scanHitAreas 는 page.evaluate / locator.evaluate 에 그대로 넘기는 **자급식** 함수다. Playwright 는 함수를 문자열로 보내므로,
// 이 함수 밖의 상수 · 도우미를 참조하면 브라우저에서 ReferenceError 가 난다. 부르기 전에 마우스를 치워라(page.mouse.move(0, 0)) —
// 스크롤 중 마우스 밑을 지나는 막힌 단추가 사유 미리보기를 열어 아래쪽 입력을 덮는다(K5 10-02, 모바일 .os-reason__tip 시트).

export interface HitAreaArgs {
  /** 잴 대상 선택자. */
  readonly selector: string;
  /** 누를 영역 하한(px). 0 이면 크기는 보지 않고 덮임만 본다. */
  readonly min: number;
}

export interface HitAreaReport {
  /** `태그 "이름" W×H` — 누를 영역이 min 미만. */
  readonly small: string[];
  /** `덮임 태그 "이름" ← 덮은 것` — 끝까지 가운데가 다른 요소에 덮임(열린 층이 없는 화면이면 결함). */
  readonly covered: string[];
  /** `못 잼 태그 "이름"` — 어느 스크롤로도 화면에 들일 수 없었음. */
  readonly missed: string[];
}

export async function scanHitAreas(node: Element, args: HitAreaArgs): Promise<HitAreaReport> {
  const { selector, min } = args;
  const small: string[] = [];
  const covered: string[] = [];
  const frame = () => new Promise((resolve) => requestAnimationFrame(() => resolve(null)));
  const shown = (el: Element) => {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const style = getComputedStyle(el);
    return style.visibility !== 'hidden' && style.display !== 'none';
  };
  const nameOf = (el: Element) => (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 24);
  const tagOf = (el: Element) => el.tagName.toLowerCase();
  const labelOf = (el: Element): Element | null => {
    if (!(el instanceof HTMLInputElement || el instanceof HTMLSelectElement || el instanceof HTMLTextAreaElement)) return null;
    return el.closest('label') ?? (el.id ? document.querySelector(`label[for="${CSS.escape(el.id)}"]`) : null);
  };
  // 가운데에서 바깥으로 훑은 적중 범위. 가운데가 다른 요소면 { coveredBy }.
  const measure = (el: Element): { w: number; h: number } | { coveredBy: Element | null } | null => {
    const r = el.getBoundingClientRect();
    const cx = r.left + r.width / 2;
    const cy = r.top + r.height / 2;
    if (cx < 0 || cy < 0 || cx >= innerWidth || cy >= innerHeight) return null;
    const mine = (x: number, y: number) => {
      if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return false;
      const hit = document.elementFromPoint(x, y);
      return !!hit && (hit === el || el.contains(hit));
    };
    if (!mine(cx, cy)) return { coveredBy: document.elementFromPoint(cx, cy) };
    const reach = (dx: number, dy: number) => { let d = 0; while (d < 64 && mine(cx + dx * (d + 1), cy + dy * (d + 1))) d += 1; return d; };
    return { w: reach(-1, 0) + reach(1, 0) + 1, h: reach(0, -1) + reach(0, 1) + 1 };
  };
  const inFixedLayer = (n: Element | null) => {
    for (let at: Element | null = n; at; at = at.parentElement) if (getComputedStyle(at).position === 'fixed') return true;
    return false;
  };
  const coverName = (n: Element | null) => (n ? (typeof n.className === 'string' && n.className) || n.tagName : '없음');
  // 결과를 적는다. 라벨 있는 입력은 입력 · 라벨 중 넓이가 큰 쪽 하나(덮인 라벨은 쓰지 않음). 덮였으면 false(다음 화면에서 다시).
  const record = (el: Element, got: { w: number; h: number } | { coveredBy: Element | null }, final: boolean): boolean => {
    if ('coveredBy' in got) {
      if (inFixedLayer(got.coveredBy) && !final) return false;
      covered.push(`덮임 ${tagOf(el)} "${nameOf(el)}" ← ${coverName(got.coveredBy)}`);
      return true;
    }
    let { w, h } = got;
    const label = labelOf(el);
    if (label && shown(label)) {
      const byLabel = measure(label);
      if (byLabel && !('coveredBy' in byLabel) && byLabel.w * byLabel.h > w * h) ({ w, h } = byLabel);
    }
    if (min > 0 && (w < min || h < min)) small.push(`${tagOf(el)} "${nameOf(el)}" ${w}×${h}`);
    return true;
  };

  const savedX = scrollX;
  const savedY = scrollY;
  const moved: Array<[Element, number, number]> = [];
  const remember = (el: Element) => { for (let p = el.parentElement; p; p = p.parentElement) moved.push([p, p.scrollTop, p.scrollLeft]); };
  const targets = Array.from(node.querySelectorAll(selector)).filter(shown);
  const seen = new Set<Element>();
  const step = Math.max(200, Math.floor(innerHeight * 0.8));
  for (let y = 0; ; y += step) {
    window.scrollTo(0, y);
    await frame();
    const last = y + innerHeight >= document.documentElement.scrollHeight;
    for (const el of targets) {
      if (seen.has(el)) continue;
      let r = el.getBoundingClientRect();
      const middle = r.top + r.height / 2;
      if (!(r.top >= 0 && r.bottom <= innerHeight) && !(last && middle >= 0 && middle < innerHeight)) continue;
      // 가로로 미는 줄 안에서 화면 옆에 나가 있으면 그 줄만 밀어 들인다 — 세로는 이미 다 들어 있어 움직이지 않는다.
      if (r.left + r.width / 2 < 0 || r.left + r.width / 2 >= innerWidth) {
        remember(el);
        el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        await frame();
        r = el.getBoundingClientRect();
      }
      let got = measure(el);
      if (got && 'coveredBy' in got && !inFixedLayer(got.coveredBy)) {
        // 화면 안이어도 미는 줄(overflow)에 가려 있을 수 있다 — 한 번 들여 보고 다시 본다.
        remember(el);
        el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        await frame();
        got = measure(el);
      }
      if (!got) continue;
      if (record(el, got, last)) seen.add(el);
    }
    if (last) break;
  }
  // 창 스크롤로 못 본 것(안쪽 세로 스크롤 상자 등): 그 요소를 화면 가운데로 들여 한 번 더 잰다.
  for (const el of targets) {
    if (seen.has(el)) continue;
    remember(el);
    el.scrollIntoView({ block: 'center', inline: 'nearest' });
    await frame();
    const got = measure(el);
    if (got && record(el, got, true)) seen.add(el);
  }
  for (let i = moved.length - 1; i >= 0; i -= 1) { const [p, top, left] = moved[i]; p.scrollTop = top; p.scrollLeft = left; }
  window.scrollTo(savedX, savedY);
  const missed = targets.filter((el) => !seen.has(el)).map((el) => `못 잼 ${tagOf(el)} "${nameOf(el)}"`);
  return { small, covered, missed };
}
