# 저장된 임명 제안의 응답 writer

`OfficeOfferResponseExecutor`는 실제 `InMemoryTurnWorld`의 후보 장수 `meta.officeAppointmentOffer`를 읽고, native `OfficeAppointmentFlow.respond` 결과를 `ChangeRecorder.diffGeneral`에 먼저 기록한 후 dirty-free 메모리에 반영한다. 일반 flush의 general metadata patch 경로를 사용한다. 다른 metadata와 발행자 행은 보존한다.

호출자는 직렬 데몬 mutation unit 안에서 인증된 ownerUserId, 실제 worldId, 후보 actorId와 읽었던 **전체 native offer**를 전달한다. 세계·후보·현재 소유자·전체 원본의 일치가 필요하며, 제안 부재·손상·시계 오류·변경된 원본은 쓰기 전에 실패한다. 전체 값 비교는 이 메모리 호출의 stale-source guard다. 별도 영속 revision, requestId 중복 처리, 변경 후 복귀한 원본(ABA) 방지를 보장하지 않는다. prepared generation/tombstone의 recorder 거절 시 메모리는 변경되지 않는다. unit rollback 및 JDBC flush 성공·재시도는 호출자의 기존 경계 책임이다.

원본 issuedAt/dueAt·당사자·관직·관할·부임지는 유지하고 status만 바꾼다. native OFFICE 규칙대로 dueAt 이상이면 늦은 거절도 ACCEPTED다. 이 규칙은 봉신 제안의 만료 정책을 정의하지 않는다. 최신 terminal 원본에 대한 응답은 무변경이며, 과거 PENDING 원본을 재전송하면 stale source로 거절한다.

기존 `OfficeStoredOfferView`는 저장된 결과를 `CourtOfferDto`의 sourceRef·terms·dueAt·state로 읽는다. 새 접수 입력·HTTP 응답 옵션·opaque lookup·daemon 등록은 추가하지 않았다. 현재 실제 생산 caller 연결은 없다. 새 임명 발행, jurisdiction snapshot producer, 재임 생성·도착 판정·권한 부여도 이 writer의 완료 범위 밖이다.

집중 시험은 실제 world/recorder의 응답 delta, 실패 시 불변성, prepared generation, checkpoint rollback, MetaJson cold codec 및 기존 DTO 소비를 다룬다. 실제 PostgreSQL flush·cold boot·운영 호출은 별도 검증 전 미완이다. 시험 결과는 메타레포 `reports/opensamguk/tasks/2026-10-06-c5-office-offer-response.md`에 기록한다.
