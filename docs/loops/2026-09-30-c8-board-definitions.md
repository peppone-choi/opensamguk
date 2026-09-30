# G-01 게시판 정의 CRUD

## 안 된 것

- 새 Kotlin/DB 테스트는 작성했고 아직 실행하지 않았다. 화면 수신·실제 운영 CRUD·운영 migration 승인 없음.
- C0는 V72를 예약했으며 V70→V71→V72 순서, 운영 DB 승인 전 draft/ready 한 건 원칙을 유지한다. G-02 서버 기본 false는 기존에 있었고 K5 화면 증거가 남았다.

## 변경·호환

- 기존 category JSON 문자열과 여섯 기본 키를 보존하면서 검증된 문자열 키 값으로 바꾼다. 게시판 정의 table/key FK가 실제 존재를 판정한다. 적용된 V40/V54는 수정하지 않는다.
- 공개 GET /board/categories는 기존 category/count에 boardId/key/name/sortOrder/writable/createdAt을 추가한다. DB 정의 순서(sortOrder/id), 빈 게시판도 count0이다.
- 관리자 POST /board/admin/boards {key,name,sortOrder,writable}, PATCH /board/admin/boards/{id} {name?,sortOrder?,writable?}, DELETE /board/admin/boards/{id}?moveTo=...
- 키는 첫 글자 영문 대문자, 나머지는 대문자/숫자/밑줄, 1–32자다. 키는 생성 뒤 바꾸지 않는다. 이름은 trim 후 1–80자다. readonly(writable=false)는 관리자 포함 새 글·수정·댓글 작성을 거절한다. 기존 NOTICE 글 작성/이동은 계속 관리자만 가능하며 댓글 권한을 공지 글 권한으로 확대하지 않는다.
- 글이 있으면 soft-deleted 포함 모든 글을 유효한 다른 게시판으로 먼저 이동한다. 글/댓글/신고를 hard delete하지 않고 IDs/내용/날짜/삭제 표식을 보존한다. 정의 FK는 남은 글이 있는 삭제를 막는다.
- 쓰기는 정의 공유 row lock을 commit까지 보유, 삭제/설정 변경은 배타 row lock. 이동 대상 둘은 id 순으로 잠가 반대 방향 이동의 교착을 피한다.
- G-02: 공개/관리자 기본 목록은 기존 includeDeleted=false와 deleted_at IS NULL이다. 서버에서 삭제 자체를 영구 삭제로 바꾸지 않는다. K5가 명시 includeDeleted=true 요청을 제거하고 화면 수신을 검증한다.

## 테스트·위험

- 관리자 401/403/201, 새 category의 JSON 문자열/검색, 이름·순서·readonly, 없는 category fail-closed, 이동 없이 삭제 거절/자기이동/없는대상, 활성/삭제 글·댓글·신고 보존 시험을 작성했다.
- V69→V72 migration에서 이전 글 byte/컬럼 내용 보존 및 FK 음성 시험. PostgreSQL 잠금 경합에서 writer 공유 lock 동안 delete timeout, writer commit 뒤 moveTo 없이 삭제 거절, 이동 뒤 동일 post id 보존 시험을 작성했다.
- H2 test profile은 Flyway 없이 Hibernate schema를 사용하므로 여섯 정의 fixture를 초기화한다. 기존 PostgreSQL Flyway IT는 fixture 초기화를 끄고 실제 migration을 사용한다.
- naming lint 148/0/0/4676 baseline 그대로, diff 검사 성공. 실행 전에는 서버 완료로 처리하지 않는다.
- 고정 category union을 가진 K5 클라이언트는 DB 정의 목록/문자열 키를 수신해야 새 게시판을 쓸 수 있다. web/ 변경은 없다.
