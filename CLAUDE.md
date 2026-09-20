@AGENTS.md

- 최신 승인 ADR·spec·현재 구현을 따른다. 비밀값·인증 파일을 읽거나 출력하지 말고 비공개·자산 허용 범위를 지킨다. commit/push/PR/merge/deploy·삭제·재시드는 대상별 승인이 필요하며, 승인된 로컬 구현·검증은 완료까지 진행한다.
- 결정론·수치 변경 근거·로그와 삽입 순서를 보존한다. 데몬 write는 `ChangeRecorder`→`JdbcFlushExecutor`만 허용하며 부팅 seeder 예외를 유지한다. 골든을 약화하거나 가짜 데이터·202 응답으로 성공을 선언하지 않는다.
- 작업별 문서·검증은 [작업 참고](docs/development/agent-reference.md), 도메인·자산·Claude 커맨드는 [제품 상세](docs/development/product-reference.md)의 해당 절을 따른다. 영향 검사·실제 결과를 확인하고 위험 계약의 merge/deploy 전 독립 검토 및 미검증 보고를 유지한다.
