// 데스크톱 · 모바일 같은 흐름 e2e 도우미(v3.1 디자인 시스템 규칙을 브라우저에서 잰다).
// 규칙: 누르는 것 44 이상 · 호버/title 전용 정보 금지 · 가로 넘침 없음 · 지도는 「그려졌다」와 「조작된다」를 따로 본다.
import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';

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

/** 모바일은 탭, 데스크톱은 누르기. 같은 흐름 spec 이 조작 하나로 두 프로필을 다 돈다. */
export async function press(locator: Locator, testInfo: TestInfo): Promise<void> {
  if (isMobile(testInfo)) await locator.tap();
  else await locator.click();
}

export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, '가로 넘침(px)').toBeLessThanOrEqual(0);
}

const PRESSABLE = 'button, a[href], [role="button"], [role="tab"], [role="option"], [role="menuitem"], input:not([type="hidden"]), select, textarea, summary';

/** 보이는 누를 것 중 44 × 44 보다 작은 것(설명 문자열). 글 안 링크처럼 예외가 필요하면 부르는 쪽이 root 를 좁힌다. */
export async function smallTouchTargets(page: Page, root = 'body', min = 44): Promise<string[]> {
  return page.locator(root).first().evaluate((node, [selector, size]) => {
    const out: string[] = [];
    for (const el of Array.from(node.querySelectorAll<HTMLElement>(selector as string))) {
      const r = el.getBoundingClientRect();
      const style = getComputedStyle(el);
      if (r.width === 0 || r.height === 0 || style.visibility === 'hidden' || style.display === 'none') continue;
      if (r.width < (size as number) || r.height < (size as number)) {
        const label = (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 24);
        out.push(`${el.tagName.toLowerCase()} "${label}" ${Math.round(r.width)}×${Math.round(r.height)}`);
      }
    }
    return out;
  }, [PRESSABLE, min] as const);
}

/** title 로만 보이는 정보(호버 전용). 비활성 사유는 누르면 여는 시트여야 한다(ReasonTooltip). */
export async function titleOnlyInfo(page: Page, root = 'body'): Promise<string[]> {
  return page.locator(root).first().evaluate((node) =>
    Array.from(node.querySelectorAll<HTMLElement>('[title]'))
      .filter((el) => (el.getAttribute('title') ?? '').trim() !== '' && el.getBoundingClientRect().width > 0)
      .map((el) => `${el.tagName.toLowerCase()} title="${(el.getAttribute('title') ?? '').slice(0, 30)}"`));
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
