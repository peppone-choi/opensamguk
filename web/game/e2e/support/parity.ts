// 데스크톱 · 모바일 같은 흐름 e2e 도우미(v3.1 디자인 시스템 규칙을 브라우저에서 잰다).
// 규칙: 누르는 것 44 이상 · 호버/title 전용 정보 금지 · 가로 넘침 없음 · 지도는 「그려졌다」와 「조작된다」를 따로 본다.
import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';
import { scanHitAreas } from '../../../shared/e2e/hitArea';

/** 두 프로필(데스크톱 · 모바일)에서 같은 흐름으로 돈다. */
export const BOTH = '@both';
/** 모바일에서만 도는 흐름(하단 시트 · 하단 탭 등). */
export const MOBILE_ONLY = '@mobile-only';
// 백엔드 없이 Next 서버만으로 도는 spec 은 e2e/smoke/ 에 둔다(합성 자료는 page.route 로 가로챈다).
// CI web (game) 잡이 `next start` 뒤 `playwright test e2e/smoke` 를 desktop · mobile 두 프로젝트로 돌린다.
// 실스택이 필요한 spec 은 e2e/ 바로 아래에 두고 실스택 잡(유주 증거 등)이 돌린다.

export function isMobile(testInfo: TestInfo): boolean {
  return testInfo.project.name === 'mobile';
}

/**
 * 모바일은 탭, 데스크톱은 누르기 — 화면 좌표로 누른다. 같은 흐름 spec 이 조작 하나로 두 프로필을 다 돈다.
 * Playwright 의 click · tap 은 aria-disabled 를 「비활성」으로 보고 끝없이 기다리지만, v3.1 의 막힌 조작은 일부러 눌러서
 * 사유를 여는 것이다. 누르기 전에 그 자리 맨 위가 그 요소인지 확인한다(다른 상자가 덮으면 빨개진다).
 */
export async function press(locator: Locator, testInfo: TestInfo): Promise<void> {
  await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  expect(box, '누를 것이 보여야 한다').not.toBeNull();
  const x = box!.x + box!.width / 2;
  const y = box!.y + box!.height / 2;
  const onTop = await locator.evaluate((el, [px, py]) => {
    const hit = document.elementFromPoint(px, py);
    return hit !== null && (hit === el || el.contains(hit));
  }, [x, y] as const);
  expect(onTop, '누를 자리 맨 위가 그 요소여야 한다(elementFromPoint)').toBe(true);
  const page = locator.page();
  if (isMobile(testInfo)) await page.touchscreen.tap(x, y);
  else await page.mouse.click(x, y);
}

export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, '가로 넘침(px)').toBeLessThanOrEqual(0);
}

const PRESSABLE = 'button, a[href], [role="button"], [role="tab"], [role="option"], [role="menuitem"], input:not([type="hidden"]), select, textarea, summary';

/**
 * 누를 영역이 44 × 44 보다 작은 것(설명 문자열 `태그 "이름" W×H`). 「누를 영역」은 상자 크기가 아니라 가운데에서 바깥으로 훑은
 * elementFromPoint 적중 범위다(K0 2026-10-02, 두 앱 공용 web/shared/e2e/hitArea.ts). 패딩 · ::before 로 넓힌 만큼은 누를 수 있고,
 * 겹친 상자가 가린 만큼은 누를 수 없다. 라벨 있는 입력은 라벨까지 잰다. 글 안 링크처럼 예외가 필요하면 부르는 쪽이 root 를 좁힌다.
 * 적중 범위를 못 잰 것(가운데가 끝까지 덮임 · 화면에 못 들임)은 옛 뜻대로 상자 크기로 재서 넣는다(boxSmall) — 덮인 30×30 단추가
 * 조용히 빠지지 않게(리뷰 #1209). 덮임 자체는 여기서 세지 않는다: 시트 · 모달이 열린 화면에서 그 뒤 단추가 덮이는 것은 맞다.
 * 덮임은 coveredTargets 로 본다.
 */
export async function smallTouchTargets(page: Page, root = 'body', min = 44): Promise<string[]> {
  await page.mouse.move(0, 0); // 스크롤 중 사유 미리보기가 열려 아래 입력을 덮지 않게(K5 10-02)
  const report = await page.locator(root).first().evaluate(scanHitAreas, { selector: PRESSABLE, min });
  return [...report.small, ...report.boxSmall];
}

/**
 * 누를 것의 가운데가 끝까지 다른 상자에 덮인 것 · 화면에 들일 수 없었던 것(설명 문자열). 한 화면씩 내려가며 재고,
 * 붙박인 층(아래 탭 · 떠 있는 단추)에 걸친 것은 스크롤해서 다시 잰다 — 첫 화면 위치만 보지 않는다(K10 10-02 오탐 5 · 8 · 9).
 * 예전 spec 마다 복사해 둔 coveredIn 을 이것으로 바꿨다. targets 는 그 spec 이 보던 선택자.
 */
export async function coveredTargets(root: Locator, targets = 'a, button, select, input'): Promise<string[]> {
  await root.page().mouse.move(0, 0);
  const report = await root.evaluate(scanHitAreas, { selector: targets, min: 0 });
  return [...report.covered, ...report.missed];
}

/** title 로만 보이는 정보(호버 전용). 비활성 사유는 누르면 여는 시트여야 한다(ReasonTooltip). */
export async function titleOnlyInfo(page: Page, root = 'body'): Promise<string[]> {
  return page.locator(root).first().evaluate((node) =>
    Array.from(node.querySelectorAll<HTMLElement>('[title]'))
      .filter((el) => (el.getAttribute('title') ?? '').trim() !== '' && el.getBoundingClientRect().width > 0)
      .map((el) => `${el.tagName.toLowerCase()} title="${(el.getAttribute('title') ?? '').slice(0, 30)}"`));
}

/**
 * 글자가 잘렸는데 「…」를 못 그리는 상자(설명 문자열). 넘친(scrollWidth > clientWidth) 상자 중 글자를 직접 가진 것이
 * 밀 수 없게 잘리고(overflow hidden · clip) `text-overflow: ellipsis` 를 그릴 수 없는 모양(flex · grid 상자, ellipsis 아님)이면 잡는다.
 * flex 상자의 글자는 이름 없는 flex 항목이라 ellipsis 가 그려지지 않는다 — 글자를 span 으로 감싸 그 span 이 줄여야 한다.
 */
export async function clippedWithoutEllipsis(page: Page, root = 'body'): Promise<string[]> {
  return page.locator(root).first().evaluate((node) => {
    const out: string[] = [];
    for (const el of [node as HTMLElement, ...Array.from(node.querySelectorAll<HTMLElement>('*'))]) {
      if (el.scrollWidth <= el.clientWidth + 1) continue;
      const style = getComputedStyle(el);
      if (style.overflowX !== 'hidden' && style.overflowX !== 'clip') continue;
      const ownText = Array.from(el.childNodes).some((n) => n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim() !== '');
      if (!ownText) continue;
      if (style.textOverflow === 'ellipsis' && !/flex|grid/.test(style.display)) continue;
      out.push(`${el.tagName.toLowerCase()}.${String(el.className).split(' ')[0]} 「${(el.textContent ?? '').trim().slice(0, 16)}」`);
    }
    return out;
  });
}

/** 지도 가운데를 실제로 누를 수 있는지(겹친 투명 상자가 먹지 않는지). 가운데 요소가 selector 안에 있어야 한다. */
export async function expectCenterHitsMap(page: Page, selector: string): Promise<void> {
  const map = page.locator(selector).first();
  const box = await map.boundingBox();
  expect(box, `${selector} 가 보여야 한다`).not.toBeNull();
  const hit = await page.evaluate(([x, y, sel]) => {
    const el = document.elementFromPoint(x as number, y as number);
    const target = document.querySelector(sel as string);
    return el !== null && target !== null && (el === target || target.contains(el));
  }, [box!.x + box!.width / 2, box!.y + box!.height / 2, selector] as const);
  expect(hit, `${selector} 가운데를 누르면 지도에 닿아야 한다(elementFromPoint)`).toBe(true);
}
