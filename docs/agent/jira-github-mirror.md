# Jira ↔ GitHub 이슈 미러 규약

## 역할 분담

| 시스템 | 역할 |
|---|---|
| **Jira (OPENSAM)** | 기획·상태 **정본**. 티켓 생성, 우선순위, 상태 전이(할 일/진행 중/완료)는 여기서 결정한다. |
| **GitHub 이슈** | **에이전트 작업 표면**. 에이전트에게 이슈 하나를 그대로 던지면 작업이 되도록 Jira 본문 전문을 담는다. |
| **코드 / PR** | 최종 진실. 문서·티켓과 어긋나면 코드가 이긴다. |

진실 순서: **코드 > PR > GitHub 이슈 > Jira**.

## 자동 동기화는 없다

Jira ↔ GitHub 사이에 자동 동기화 봇이나 웹훅이 **없다**. 미러는 사람 또는 에이전트가 **수동으로** 만든다.
따라서 Jira 본문을 고쳤다면 GH 이슈 본문도 손으로 고쳐야 한다(반대도 마찬가지).

**새 Jira 티켓을 만들면 같은 턴에 GH 미러도 만든다.** 미루면 갭이 쌓인다.

## 미러 규약

대상: `statusCategory != Done` 인 티켓만. 완료된 티켓은 미러하지 않는다.

### 제목
```
[OPENSAM-###] <Jira summary 그대로>
```

### 라벨
| 조건 | 라벨 |
|---|---|
| 항상 | `jira-mirror` (설명: "Jira OPENSAM 미러 이슈") |
| issuetype = 에픽 | `epic` 추가 |
| priority = Highest | `priority-now` |
| priority = High | `priority-next` |
| priority = Medium / Low | 우선순위 라벨 없음 |

라벨이 없어서 `gh issue create` 가 실패하면 `gh label create <name> -d "<설명>"` 으로 만들고 진행한다.

### 본문
Jira description **전문을 요약 없이 그대로** 옮기고, 마지막에 푸터를 붙인다:

```
---
- Jira execution record: [OPENSAM-###](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-###)
- Source of truth: 위 Jira 티켓 본문; 이 GitHub 이슈는 에이전트 작업 표면이다.
```

에픽/선행 관계를 아는 경우 푸터에 한 줄 더 붙일 수 있다:
`- Parent Epic: #NNN ([OPENSAM-##](...))` 또는 `- Blocked by: #NNN ([OPENSAM-##](...))`.

요약하지 마라. 다른 에이전트는 이 본문만 보고 작업한다.

### 역링크
GH 이슈를 만든 뒤 해당 Jira 이슈에 코멘트 한 줄을 남긴다:
```
GitHub 미러: https://github.com/peppone-choi/opensamguk/issues/NNN
```

### 중복 방지
생성 전 반드시 재확인한다:
```bash
gh issue list --search "OPENSAM-### in:title" --state all --json number,title
```

## 갭 재산출

이미 미러된 키:
```bash
gh issue list --state all --limit 400 --json title --jq '.[].title' \
  | grep -o 'OPENSAM-[0-9]*' | sort -u
```
이 목록과 Jira 의 미완료 티켓 목록을 diff 해서 누락분을 찾는다. **추측하지 말고 실제 diff 로 확정한다.**

## Atlassian MCP 페이지네이션 우회

`searchJiraIssuesUsingJql` 은 **호출당 5건만** 돌려주고 `nextPageToken`/`endCursor` 를 신뢰할 수 없다
(`maxResults` 를 100으로 줘도 5건, `hasNextPage` 가 false 로 오면서 `remainingCount` 만 남는다).
`fields` 파라미터도 무시되어 `description` 은 항상 함께 온다.

우회:
- JQL 을 **5개 키 창**으로 쪼개 여러 번 호출한다:
  `key in (OPENSAM-176,OPENSAM-177,OPENSAM-178,OPENSAM-179,OPENSAM-180)`
- 날짜 구간으로 쪼개는 것도 가능하다: `created >= "YYYY-MM-DD" AND created < "YYYY-MM-DD"`
- 단건 본문이 필요하면 `getJiraIssue` 를 키별로 호출한다.
- 미완료 총량만 알고 싶으면 `project = OPENSAM AND statusCategory != Done` 을 한 번 호출해
  응답의 `remainingCount` + 반환된 5건 수를 더한다.

키 창이 많을 때는 범위를 나눠 서브에이전트에 병렬 위임하고, 범위를 **겹치지 않게** 준다(중복 생성 방지).

## 금지

- 에이전트는 Jira **상태를 전이하지 않는다**. 읽기 + 코멘트만.
- Done 티켓 미러 금지.
- 읽지 못한 티켓은 **UNKNOWN** 으로 보고한다. 지어내지 않는다.
