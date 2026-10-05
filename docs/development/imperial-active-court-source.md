# ACTIVE 황통 내부 읽기 source

`ImperialActiveCourtSourceReader.read()`는 향후 상세 읽기 소비자를 위한 내부 source다. Spring 프록시를 통해 호출하면 read-only REPEATABLE_READ 트랜잭션을 열고 기존 `ImperialPresenceReader`를 같은 트랜잭션에서 호출한다. 기존 reader의 황제 이름·공간·지형 판본·조정 城 검증을 재사용한 뒤 같은 process-world 城 행에서 현재 조정 이름을 붙인다.

| 상태 | context | lines | 의미 |
|---|---|---|---|
| READY | 실제 worldId와 게임 Phase | ACTIVE badge에 조정 城 이름을 결합한 목록 | 빈 목록도 ACTIVE 자료에만 한정된다. VACANT/ENDED나 전체 황실 부재를 뜻하지 않는다. |
| NOT_SEEDED | 실제 worldId와 게임 Phase | null | 기존 imperialWorld key 부재. 정상 빈 목록으로 바꾸지 않는다. |
| UNAVAILABLE | null | null | process-world·시점·기존 presence·조정 행의 검증 실패. 부분 목록을 반환하지 않는다. |

게임 시점은 durable revision·CAS token이 아니다. 조정 城 미지정은 ID와 이름 모두 null이며 황제 위치로 채우지 않는다. 실제 이름이 blank면 이름만 null이다. 같은 조정 城을 쓰는 ACTIVE badge는 이름을 한 번 조회한다. 반환 목록은 수정할 수 없는 복사본이며 JPA entity를 보관하지 않는다.

현재 HTTP endpoint/controller·관찰자 인증/ACL·공개 root/snapshot DTO는 추가하지 않는다. H05/H06(섭정·조정 국), VACANT/ENDED 공개, nomination key/writer, D101 actor/seed도 이 source에 포함하지 않는다. 외부 소비 계약과 권한을 확정한 뒤 별도 변경에서 연결한다.

단위 시험은 시점·이름·null·실패 전파·복사·기존 presence 호출을 확인한다. 트랜잭션 annotation과 단위 mock 시험은 PostgreSQL의 동시 갱신 snapshot을 입증하지 않는다. JPA/JDBC가 실제 동일 트랜잭션에 참여하는지는 별도 두 연결 통합 시험으로 검증해야 한다.
