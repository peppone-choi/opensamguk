# 배포 job의 미사용 actions/checkout 제거 — 자체 리뷰

Scope: .github/workflows/deploy.yml 의 self-hosted `deploy` job 스텝 목록.
Verdict: cleared

## 1. 증상

main 배포가 연속 3회 실패했다 — run `32035675655`(4be3d943) · `32037387818`(be565ee5) ·
`32038640965`(c364ebf3). 세 번 다 같은 지점이다:

```
##[error]Response status code does not indicate success: 429 (Too Many Requests).
##[error]Failed to download archive 'https://codeload.github.com/actions/checkout/tar.gz/11d5960a...' after 3 attempts.
```

`Set up job` 단계에서 죽으므로 배포 스크립트는 한 줄도 실행되지 않았다. GitHub 호스티드 job
(build-jvm/build-web)은 같은 커밋에서 통과한다 — 429는 self-hosted 러너
(`gcp-prod-opensamguk`, GCP VM) IP에만 걸린다.

## 2. 근본 원인

`deploy` job의 `actions/checkout@v4`는 **한 번도 쓰이지 않는다.** 세 스텝 전부
`$HOME/opensamguk-docker`(`$STACK`)로 `cd`해서 그 저장소의 compose 파일과 `.env`만 다룬다.
워크스페이스 파일·`github.workspace`·저장소 상대 경로 참조가 0건이다(grep 확인).

즉 쓰지도 않는 tarball 다운로드가 배포 전체의 단일 실패점이었다.

## 3. 고친 것

`deploy` job에서 `- uses: actions/checkout@v4` 한 줄을 제거하고, 왜 없는지(재추가 방지)를
주석으로 남겼다. GitHub 호스티드 job 두 개의 checkout은 실제로 소스를 빌드하므로 그대로 둔다.

## 4. 스스로 공격해 본 것

- **정말 워크스페이스를 안 쓰나?** `deploy` job 본문(216행~) 전체에서 `GITHUB_WORKSPACE`,
  `github.workspace`, 저장소 상대 경로(`tools/`, `scripts/`, `infra/`, `./`) 참조를 grep해 0건 확인.
  등장하는 경로는 `$STACK` 하위 `docker-compose.shared.yml`·`.env`뿐이고 이는 sibling 저장소 파일이다.
- **YAML이 깨졌나?** `python3 -c "yaml.safe_load(...)"`로 파싱하고 `deploy` job 스텝이
  `Deploy shared stack on box` / `Preserve game server version pins` /
  `Verify (health + registered game servers)` 3개로 남았음을 확인했다.
- **429가 일시적이라 그냥 기다리면 되지 않나?** 26분 간격 3회 연속 실패다. 기다리는 것은
  고치는 것이 아니고, 이 스텝은 성공해도 무가치하다 — 제거가 더 짧은 diff이자 근본 해결이다.
- **다른 러너 액션도 같은 위험 아닌가?** `deploy` job에 남은 `uses:`는 이제 0개다. 나머지
  `uses:`(setup-java, setup-gradle, docker/login-action)는 전부 GitHub 호스티드 job 소속이다.

## 5. 검증

`deploy.yml`은 워크플로 파일이라 로컬 실행 증거를 만들 수 없다. **판정은 이 PR이 main에 머지된
뒤의 실제 배포 run으로 한다** — `Set up job`이 액션 다운로드 없이 통과하면 확정이다. 그 전까지
이 수정의 라이브 효과는 `채점대기`다.

## 6. 남긴 것

- 러너 IP의 codeload 429 자체는 해결하지 않았다(호스트/네트워크 사안). 앞으로 `deploy` job에
  액션을 추가하면 같은 실패가 돌아온다 — 주석이 그 경고다.
