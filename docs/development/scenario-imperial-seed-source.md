# 명시적 황실 seed 선언·실제 ID 반환부

이 내부 source는 기존 general ID 기반 황통 모델과 world_state.meta.imperialWorld schema1을 재사용한다. C6는 명시적 선언 decoder와 실제 생성 ID를 검증하는 payload 반환부를 맡는다. ScenarioJson/ScenarioImporter/ScenarioSeedRunner 소비 연결은 C4 소유이며 이 PR에서 수정하지 않는다.

현재 최초 회귀가 명시적 선언의 실제 decode를 확인한다. 구현과 정상 CI 결과는 후속 커밋으로 갱신한다. 3190 actor 추가·능력/생몰/위치/초기 정통성 수치·선택 원천 JSON·운영 적재는 이 source에서 만들지 않는다. source 존재는 seed 완료나 공개 gate 통과가 아니다.
