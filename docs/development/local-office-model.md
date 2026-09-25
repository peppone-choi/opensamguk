# 지방 관직 재임·실효 관할

`logic.office`는 지방 관직 원장과 순수 판정을 제공한다. `OfficeTenure`는 임명·수락·부임·종료 순을 구분하고, `OfficeCredential`은 行·領·인장·부절·문서를 개별 이력으로 둔다. 관직명만으로 능력이 생기지 않는다. 縣令·縣長·侯國相은 기존 縣 배치·발령의 `DomesticRules.seatedMagistrate`를 읽어 투영하며 별도 재임을 쓰지 않는다.

`OfficeCapabilityResolver.actualJurisdiction`은 수락·부임, 治所 縣 소유, 재임자 위치, 관할 縣 과반 소유, 治所 창고망 연결, 현령 착석 또는 주둔군을 함께 검사한다. 실제 능력 범위는 소유하면서 창고망에도 연결된 縣으로 좁힌다. `OfficeCapabilityResolver.resolve`의 결과만 handler·NPC·사전검사에서 사용한다. 太守/國相은 郡 방침과 실효 관할 縣 공사, 刺史/牧은 州 감찰만 허용한다. 司隸는 州刺史/牧 임명 대상에서 제외한다.

게임 문턱은 [`office-rules.json`](../../data/curated/han/office-rules.json) 한 곳에 둔다. 소유 비율 50%, 동시 지방 재임 2개는 구현 에이전트가 정한 게임 값이며 사료 주장으로 표시하지 않는다. 기존 월드와 연결할 때는 R1 縣 소유·월드별 행정 오버레이·창고망·부임 위치를 하나의 `OfficeJurisdictionSnapshot`으로 투영해야 한다. 기본 지도 핀만으로 월드별 治所를 결정하지 않는다.

저장은 `localOfficeTenures`와 `officeCredentials` meta JSON에 version 1로 직렬화한다. 없으면 빈 목록, 손상·중복은 codec이 예외로 거절하고 미해결 credential 참조는 `validateTenures`가 거절한다. 이 슬라이스는 순수 모델·codec·읽기 투영이며 조정 입력, 턴 배선, DB flush, 화면 명령은 후속 통합 슬라이스가 담당한다.

후속 조정 연결의 공통 판정은 `OfficeAppointmentRules.assess`다. 군주 권한, 같은 세력 대상, 지방/중앙 구분, 치소 소유, 공석·겸직 한도를 한 함수로 판정하므로 예약 사전검사와 턴 실행이 같은 실패 사유를 돌릴 수 있다. 사람 대상의 `OfficeAppointmentOffer`는 기존 발령 `DispatchPolicy.responsePhases`를 응답 기한으로 재사용하고, 기한 도달 시 자동 수락을 우선한다. 새 `officeAppointmentOffer` meta는 version 1과 필드 집합을 엄격히 검사한다. 실제 명령 채널과 NPC·화면·저장은 공유 파일 조율 뒤 연결한다.
