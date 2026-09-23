# web 폰트 출처·라이선스

`web/gateway`·`web/game` 이 쓰는 폰트 3종의 출처와 라이선스 고지다. 세 폰트 모두 **SIL Open Font
License 1.1**(OFL-1.1)이고, 원문은 이 디렉터리에 함께 커밋해 둔다. OFL-1.1 은 웹 임베딩과 재배포를
허용하며, 고지 보존과 Reserved Font Name 비사용을 요구한다.

| 폰트 | 역할 | npm 패키지 | 업스트림 버전 | 저작권자 | 고지 원문 |
|---|---|---|---|---|---|
| Noto Serif KR | `--font-serif` — 제목·연호·인물명 | `@fontsource-variable/noto-serif-kr` 5.3.0 | Google Fonts `v31` | Google Inc. | [OFL-1.1-Noto-Serif-KR.txt](OFL-1.1-Noto-Serif-KR.txt) |
| JetBrains Mono | `--font-mono` — 수치·시각 | `@fontsource-variable/jetbrains-mono` 5.3.0 | Google Fonts `v24` | The JetBrains Mono Project Authors | [OFL-1.1-JetBrains-Mono.txt](OFL-1.1-JetBrains-Mono.txt) |
| Pretendard | `--font-sans` — 본문 | `pretendard` 1.3.9 | [orioncactus/pretendard](https://github.com/orioncactus/pretendard) | Kil Hyung-jin, Adobe(Source 파생) | [OFL-1.1-Pretendard.txt](OFL-1.1-Pretendard.txt) |

`@fontsource-variable/*` 의 고지 원문은 설치본 `node_modules/@fontsource-variable/<이름>/LICENSE`
에서 그대로 복사했다. `pretendard` npm 패키지는 고지 파일을 동봉하지 않으므로 업스트림 저장소
`main` 의 `LICENSE` 를 받아 두었다.

## self-host 계약

세 폰트 모두 **npm 패키지가 동봉한 woff2 + unicode-range 분할 CSS** 를 `app/layout.tsx` 에서
import 해 번들에 넣는다(ADR-LITE-064). 따라서

- `next build` 는 폰트 때문에 네트워크에 접속하지 않는다. `next/font/google` 은 쓰지 않는다.
- 런타임에도 CDN(`fonts.gstatic.com`·jsDelivr) 요청이 없다. 폰트는 앱 자신의 `/_next/static` 에서 나간다.
- 분할이 유지되므로 브라우저는 실제로 쓰인 글자 범위의 조각만 받는다(Noto Serif KR 은 124조각, 전량 6.3 MB).

폰트를 더하거나 버전을 올릴 때는 이 표와 고지 원문을 같이 갱신하고, 네트워크를 끊은 빌드가 통과하는지
확인한다(검증 절차는 ADR-LITE-064 참조).
