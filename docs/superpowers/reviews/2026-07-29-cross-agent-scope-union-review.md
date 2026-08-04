# Cross-agent scope union 독립 검토

- 일자: 2026-07-29
- 독립 검토자: `v1_strict_gate_root_cause`
- 검토 대상:
  - `tools/agent-system/check.py`
  - `scripts/agent/test-codex-agent-os.sh`

Scope: tools/, scripts/agent/
Verdict: cleared

## 판정 경계

이 검토는 교차 에이전트 비평 문서의 범위 판정과 회귀 검증에 한정한다. 사용자 소유 기준선인 `.codex/config.toml`의 personal model pin은 이 범위 밖이므로, 이 문서는 strict 전체 녹색을 주장하지 않는다.

## 초기 `fix-required` 사항

초기 검토에서는 다음 세 가지 결함 때문에 `fix-required` 판정이었다.

1. raw substring 비교는 `docs/webapp/`가 `app/`을, `not-tools/`가 `tools/`을 덮는 것처럼 오인할 수 있었다.
2. 단일 `cleared` 문서와 유효한 `quarantined-with-proof` 문서가 각각 범위를 충족하는 양성 회귀가 없었다.
3. `tools/assets/` 같은 child scope가 상위 `tools/` area coverage에 기여하지 못하는 false-negative가 있었다.

## 최종 구현 확인

`scope_covers_area`는 필요한 area 앞에만 path-token boundary를 요구한다. 따라서 문자열 내부에 끼어든 `app/` 및 `tools/`은 범위로 인정하지 않는 반면, `tools/assets/`는 `tools/`의 하위 경로이므로 상위 area coverage에 기여한다. 후행 경계까지 추가해 하위 경로를 배제하지 않는 점이 이 규칙의 핵심이다.

범위 집계는 메타데이터가 유효한 `cleared` 및 `quarantined-with-proof` 문서에서 얻은 coverage를 union으로 합친다. 동시에 문서마다 앵커된 범위와 판정 메타데이터가 각각 하나여야 하고, `fix-required`는 완료를 차단하며, quarantine은 `Proof`를 요구하는 기존 규칙을 유지한다. 즉 이번 수정은 scope token 인식과 union 집계의 결함만 해소했으며, 비평 증빙이나 차단 규칙을 완화하지 않았다.

## 검증 근거

| 검증 | 관측 결과 |
| --- | --- |
| `bash scripts/agent/test-codex-agent-os.sh` | PASS — raw substring 우회 거부, child-to-parent coverage, 단일 및 다중 범위 union, cleared 및 증빙 있는 quarantine, 메타데이터/차단 규칙 회귀를 포함해 통과 |
| 문서 diff check | PASS — 새 리뷰 문서에 공백 오류 없음 |
| 문서 링크 점검 | PASS — 이 문서는 외부 Markdown 링크를 추가하지 않아 해석할 링크가 없음 |

## 결론

초기 세 결함은 경로 토큰 경계와 유효 비평 범위의 union으로 해소되었고, required area를 기계적으로 누락 없이 판정한다. 위 두 대상에 대해서는 cleared이며, 범위 밖 설정 기준선까지 포함한 strict 상태는 본 검토의 주장 대상이 아니다.
