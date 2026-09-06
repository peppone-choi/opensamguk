// vitest 용 next/font/google 스텁 — Next 빌드 로더 없이 layout 을 import 할 수 있게 한다.
// 실제 폰트 self-host 는 next build 가 한다. 여기서는 className/variable 만 흉내 낸다.
type FontResult = { className: string; variable: string; style: { fontFamily: string } };
const make = (name: string) => (): FontResult => ({ className: `font-${name}`, variable: `--font-${name}-next`, style: { fontFamily: name } });
export const Noto_Serif_KR = make('serif');
export const JetBrains_Mono = make('mono');
export const Noto_Sans_KR = make('sans');
