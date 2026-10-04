// 게이트웨이 스모크 — 누를 영역(가운데에서 훑은 elementFromPoint 적중 범위). 몸통은 두 앱 공용 web/shared/e2e/hitArea.ts 로 옮겼다
// (K0 2026-10-02: 「누를 영역 44」의 뜻 하나). 이 파일은 이름(smallHitAreas)과 반환 꼴을 지키는 얇은 래퍼다 — 부르는 spec 은 그대로.
import type { Page } from '@playwright/test';
import { scanHitAreas } from '../../../shared/e2e/hitArea';

/** 누를 영역이 44 미만이거나 가운데가 끝까지 덮였거나 화면에 들일 수 없었던 것(설명 문자열). 비어 있어야 통과다. */
export async function smallHitAreas(page: Page, root: string): Promise<string[]> {
  // 스크롤 중 마우스 밑을 지나는 막힌 단추가 사유 미리보기를 열어 아래쪽 입력을 덮는다 — 재기 전에 치운다(K5 10-02).
  await page.mouse.move(0, 0);
  const report = await page.locator(root).first().evaluate(scanHitAreas, { selector: 'button, a[href], input:not([type="hidden"])', min: 44 });
  return [...report.covered, ...report.small, ...report.missed];
}
