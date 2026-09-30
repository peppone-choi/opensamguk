// 화면 폭 흉내 — 공용 useViewportClass 는 MEDIA 조건마다 matchMedia 를 물으므로, 조건별로 답해야 단이 정해진다.
// (모든 조건에 같은 값을 주면 데스크톱에서 아무 단도 맞지 않아 null 에 머문다.)
import { MEDIA } from '@opensamguk/ui';

export function setViewport(kind: 'mobile' | 'tablet' | 'desktop'): void {
    window.matchMedia = ((query: string) => ({
        matches: query === MEDIA[kind], media: query, onchange: null, addListener() {}, removeListener() {},
        addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;
}
