# 회의실 API 계약

이 문서는 #1246의 서버 구현 계약이다. 서버 검증·병합 및 화면 전환 완료 여부는 별도 증거로 확인한다. 화면 전환 전에는 기존 게시판 API를 보존한다.

## 주체와 접근

계정은 인증 principal에서, 장수·소속·월드는 서버의 현재 소유 정보에서 정한다. 요청 본문이나 조회 조건으로 계정, 장수, 소속 또는 월드를 바꿀 수 없다. 익명과 잘못된 토큰은 인증 체인에서 401 `AUTH_REQUIRED`, 인증됐지만 본인 장수를 소유하지 않은 계정은 403 `FORBIDDEN`이다. ADMIN 역할도 장수 소유나 기밀실 권한을 대신하지 않는다.

본인 장수가 무소속이면 조회는 200과 빈 `members`·`articles`, 접근 불가 및 `NO_AFFILIATION` 사유를 반환한다. 타 소속의 비공개 데이터는 조회하지 않는다. 쓰기 요청은 거절한다.

기밀실 권한은 실제 지속 군주 신원과 지정 원문에서 읽는다. 직함·주공 표지·JWT 역할만으로 권한을 만들지 않는다. 봉신 원천은 아직 연결되지 않았고 자동 지정 승계 정책은 미정이므로 원천이 불완전하면 `PARTIAL` 또는 `UNAVAILABLE`을 유지한다. 확인하지 못한 권한은 허용하지 않는다.

## 조회

`GET /api/council`의 조건은 다음과 같다. 그 밖의 조건은 400 `INVALID_REQUEST`다.

| 조건 | 값 |
| --- | --- |
| `room` | `MEETING`(기본) 또는 `SECRET` |
| `kind` | `GENERAL`, `OPERATION`, `NOTICE`; 생략하면 해당 방의 종류 전체 |
| `cursor` | 이전 응답의 `nextCursor`; 다른 월드·주체·소속·방·종류에 재사용할 수 없음 |
| `limit` | 1–50의 정수, 기본 50 |

응답은 `room`, `access`, `members`, `articles`, `nextCursor`, `blockedReason`, `membershipState`다. `access`에는 읽기·쓰기·공지·참여자 관리 가능 여부와 지정 revision이 포함된다. 글은 종류·제목·정제된 HTML 본문·작성자·시각·작전 연결·댓글을 포함하고 기밀 글에는 확인 가능한 열람자 정보를 포함한다. 참여자나 열람자 총수를 확인할 수 없으면 임의의 숫자를 채우지 않는다.

페이지 SQL은 LIMIT 전에 월드·현재 소속·방·종류와 커서의 시각·ID 조건을 함께 적용한다. 정렬은 작성 시각 및 글 ID의 내림차순이다. 글 상세 전용 GET 경로는 이 구현에 없다.

## 접수

| 경로 | 본문 |
| --- | --- |
| `POST /api/council/articles` | `room`, `kind`, `title`, `contentHtml`; 선택적 `operationId` |
| `POST /api/council/articles/{articleId}/comments` | `text` |
| `POST /api/council/articles/{articleId}/read` | 빈 본문 또는 `{}` |
| `PUT /api/council/participants/{targetGeneralId}` | 선택적 `expectedRevision`; 최초 원문 부재는 null |
| `DELETE /api/council/participants/{targetGeneralId}` | 필수 `expectedRevision` |

경로의 대상 ID는 서버가 본문에 결합한다. 본문에서 대상·주체·소속·권한을 주입하는 필드는 거절한다. 글 제목·HTML 본문과 댓글의 한도는 `CouncilRequestCodec`이 검사하며, HTML은 접수와 조회에서 정제한다. 작전 연결은 현재 월드·소속의 실제 작전만 허용한다.

202 응답 `{requestId, status: "ACCEPTED"}`는 접수 영수증이다. 실행 성공은 뜻하지 않는다. 실행기는 현재 소유·소속·군주 revision·부모 글 접근을 다시 검사하고 recorder/flush 경로로 저장한다. 참여자 지정·회수는 현재 군주만 요청할 수 있으며 지정 revision 불일치는 409 `REVISION_CONFLICT`다. 실제 권한 원문·필수 공급자를 확인할 수 없으면 503 `STATE_UNAVAILABLE`이다.

## 군주 신원과 검증

휘하 시작 시드는 각 시작 소속마다 명시적 `rulers` 선언 하나를 요구한다. 선언은 실제 활성 장수와 같은 소속 및 주공 표지에 일치해야 하고 첫 INSERT 전에 검사한다. 지속 신원은 실제 시드 선언 해시 또는 실제 정치·은퇴·생애 전환 영수증에서 생성한다. loader와 메모리 변환은 같은 지속 신원을 읽는다. 봉신 권한과 지정 자동 승계에 대한 대체 원천을 만들지 않는다.

구현 앵커는 `CouncilController`, `CouncilReader`, `CouncilAdmission`, `CouncilRequestCodec`, `CouncilHandler`, `CurrentRulerBinding`, `ScenarioImporter`다. 회귀 근거는 `CouncilSecurityChainTest`, `CouncilReaderTest`, `CouncilAdmissionTest`, `CouncilHandlerTest`, `CouncilReadRepositoryIT`, `CouncilParentRepositoryIT`, `ScenarioImporterIT`, `ScenarioBootIT`와 필수 `HotColdWorldCatalogGuardTest`다. 미실행·skip된 시험은 통과 근거로 사용할 수 없다.
