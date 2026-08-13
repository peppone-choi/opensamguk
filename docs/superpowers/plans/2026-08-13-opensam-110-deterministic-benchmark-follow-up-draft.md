# OPENSAM-110 follow-up draft — 결정적 외부 benchmark spike

- 상태: `DRAFT / NOT APPROVED`
- 입력: [타 삼국지 게임 조사](../research/2026-08-13-opensam-110-other-three-kingdoms-games.md)
- 구현·dependency 승인: 없음

## 목적

Late Eastern Han Dynasty의 공개 계약을 코드 복제나 runtime dependency 없이 읽기 전용으로
비교해, 오픈삼국 v2 후보가 반드시 답해야 하는 authority·seed·snapshot·replay 질문을
acceptance vocabulary로 만든다.

## 제안 범위

1. 외부 repository의 exact commit, license, authoritative server boundary, seeded PRNG claim,
   thin-client contract를 기록한다.
2. 오픈삼국의 `WorldId`, `worldVersion`, command inbox/result/outbox, `RandUtil`,
   `ChangeRecorder`, flush/restart 계약과 항목별로 비교한다.
3. 같은 snapshot+ordered commands+seed에서 state hash와 reason log가 같은지를 확인할 수 있는
   **오픈삼국 자체의** 최소 test vocabulary를 제안한다.
4. multiplayer 없음과 durable SQLite 미구현을 명시해 외부 prototype을 완성된 architecture
   선례로 승격하지 않는다.

## 비범위

- 외부 source 복사, vendoring, dependency 추가, asset/data 수집
- 외부 게임 실행 결과를 오픈삼국 golden으로 사용
- v1 PHP oracle 또는 `LiteHashDrbg` 교체
- benchmark 결과만으로 v2 runtime 구현 승인

## 초안 AC

- source claim마다 exact commit permalink와 `OBSERVED | CLAIMED | UNKNOWN` 등급이 있다.
- deterministic test vocabulary가 snapshot identity, ordered input, seed, draw cursor, state hash,
  event/reason log, flush/restart boundary를 빠짐없이 구분한다.
- 외부 구현과 다른 선택은 divergence가 아니라 오픈삼국 자체 계약으로 설명된다.
- 외부 source/asset가 repository diff에 들어오지 않고 dependency manifest도 바뀌지 않는다.
- 독립 reviewer가 license/provenance와 architecture overclaim을 검토한다.

## 예상 비용·위험

- CQRS 비용: **낮음(연구)**. 실제 harness 채택 시 별도 구현 티켓과 비용 산정이 필요하다.
- 위험: README claim을 runtime proof로 오인, MIT code와 별도 media license 혼동, single-player
  authoritative server를 multiplayer safety의 증거로 오인, 외부 test count를 품질 보증으로 오인.
