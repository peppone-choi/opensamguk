/**
 * 화면 폭 3단(v3.1 디자인 시스템 — docs/design/ui-v3/v31system.py BP, 사용자 승인 전 값. 승인에서 바뀌면 여기를 고친다).
 * 모바일 < 768 ≤ 태블릿 < 1200 ≤ 데스크톱. 화면마다 따로 px 를 적지 않고 여기 값만 쓴다.
 * CSS 는 @media 안에서 변수를 못 쓰므로 MEDIA 문자열을 그대로 옮겨 쓰고, tokens.css 의 --bp-* 는 같은 값의 참고값이다.
 */
export const BREAKPOINTS = { tablet: 768, desktop: 1200 } as const;

export type ViewportClass = 'mobile' | 'tablet' | 'desktop';

/** matchMedia · CSS @media 조건. 경계 바로 아래는 .98 로 잘라 두 조건이 겹치지 않게 한다. */
export const MEDIA = {
  mobile: `(max-width: ${BREAKPOINTS.tablet - 0.02}px)`,
  tablet: `(min-width: ${BREAKPOINTS.tablet}px) and (max-width: ${BREAKPOINTS.desktop - 0.02}px)`,
  desktop: `(min-width: ${BREAKPOINTS.desktop}px)`,
  tabletUp: `(min-width: ${BREAKPOINTS.tablet}px)`,
} as const;

export function viewportClass(width: number): ViewportClass {
  if (width >= BREAKPOINTS.desktop) return 'desktop';
  if (width >= BREAKPOINTS.tablet) return 'tablet';
  return 'mobile';
}
