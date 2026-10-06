# 응답 완료 공개 조서 내부 source

`PublicRespondedEdictSourceReader.read()`는 기존 `world_state.meta.imperialEdicts` schema1과 `ImperialEdictProjection`의 공개 부분집합을 재사용한다. viewerGeneralId/viewerFactionId는 null, authorizedCourtGeneralIds는 emptySet으로 고정한다. 기존 모델에서 비밀 아닌 RESPONDED만 FULL로 반환한다. proposer·courier·court insider·받는 세력의 사적 시야를 추론하지 않는다.

D29가 확정한 비밀 아닌 조서의 응답 뒤 공개 범위를 준비하는 내부 source이며 HTTP/controller·새 공개 ACL·mutation writer는 추가하지 않는다. 공개 응답 DTO/root/snapshot 소비 연결은 별도 계약 뒤 처리한다. 내부 source가 있다는 것은 실제 조서 writer·행정 배달·관직 재임·운영 초기화가 완료됐다는 뜻이 아니다.

| 상태 | context | records |
|---|---|---|
| READY | 실제 worldId와 게임 Phase | 검증된 공개 부분집합. 실제 seeded empty 또는 전부 비공개라면 빈 목록. 원본 총 수·숨긴 ID·본문은 없음. |
| NOT_SEEDED | 실제 worldId와 게임 Phase | null. 실제 codec key 부재. |
| UNAVAILABLE | null | null. world/clock/codec 손상. 부분 공개 성공을 반환하지 않음. |

반환 목록은 수정 불가 복사본이며 원본 world/entity/raw edict를 보관하지 않는다. 게임 Phase는 durable revision/CAS token이 아니다. 단위 시험은 실제 codec/projection과 secret·미응답·빈/부재·손상·실패 전파를 확인한다. Spring read-only REPEATABLE_READ annotation과 mock 시험은 PostgreSQL 동시 갱신 snapshot의 실증이 아니다.
