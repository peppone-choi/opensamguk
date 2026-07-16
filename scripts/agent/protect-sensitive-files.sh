#!/usr/bin/env bash
# protect-sensitive-files.sh — 민감/보호 파일 가드
#
# 두 가지 모드:
#  1) Claude Code hook 모드 (stdin으로 JSON: {tool_name, tool_input:{file_path,...}})
#     exit 0 = 허용, exit 2 = 차단(stderr가 에이전트에게 피드백으로 주입됨)
#  2) 수동/Codex 모드: scripts/agent/protect-sensitive-files.sh <path> [read|write]
#
# 정책 (근거: CLAUDE.md 보안 절, 패러티 규율 5조):
#  - 시크릿(.env*, *.pem, *.key, credentials*, secrets*, tfstate)은 읽기/쓰기 모두 차단.
#    (.env.example / .env.headroom.example 은 템플릿이므로 허용)
#  - 골든 픽스처(logic|common **/resources/golden/**)는 쓰기만 차단 — 골든은 실 PHP
#    캡처(tools/php-golden/)로만 갱신한다. 읽기는 허용.
#  - legacy/ 는 오라클 분석을 위해 읽기 허용, 쓰기 차단(원작을 절대 수정하지 않음).
#  - .mcp.json 은 커밋되는 파일이므로(ADR-LITE-007) 쓰기 내용에서 토큰 패턴을 스캔해 차단.
set -u

FILE_PATH="" ; MODE="write" ; TOOL_NAME="" ; MCP_TOKEN_PATTERN=""

if [ $# -ge 1 ]; then
  FILE_PATH="$1"; MODE="${2:-write}"
else
  INPUT="$(cat)"
  eval "$(printf '%s' "$INPUT" | python3 -c '
import json,sys,shlex,re
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
tool=d.get("tool_name","")
ti=d.get("tool_input") or {}
path=ti.get("file_path") or ti.get("path") or ""
hit=""
if path.replace("\\\\","/").split("/")[-1] == ".mcp.json":
    text=(ti.get("content") or "")+"\n"+(ti.get("new_string") or "")
    m=re.search(r"(sk-ant-[\w-]{8,}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[\w]{20,}|xox[baprs]-[\w-]{10,}|AKIA[0-9A-Z]{16}|glpat-[\w-]{20,}|sntrys_[\w-]{10,}|eyJ[\w-]{20,}\.[\w-]{10,}\.[\w-]{10,})",text)
    if m:
        hit=m.group(1)[:10]+"…"
    else:
        m2=re.search(r"\"(token|apiKey|api_key|password|secret|authorization)\"\s*:\s*\"(?!\$\{)[^\"]+\"",text,re.I)
        if m2: hit="필드 "+m2.group(1)
print("TOOL_NAME=%s" % shlex.quote(tool))
print("FILE_PATH=%s" % shlex.quote(path))
print("MCP_TOKEN_PATTERN=%s" % shlex.quote(hit))
')"
  case "$TOOL_NAME" in
    Read|Glob|Grep) MODE="read" ;;
    *) MODE="write" ;;
  esac
fi

[ -z "$FILE_PATH" ] && exit 0

# 경로 구분자 정규화 (크로스플랫폼 함정 방지)
NORM="$(printf '%s' "$FILE_PATH" | tr '\\' '/')"
BASE="$(basename "$NORM")"

deny() { echo "BLOCKED: $1" >&2; echo "안전한 대안: $2" >&2; exit 2; }

# 1) 시크릿 — 읽기/쓰기 모두 차단
case "$BASE" in
  .env.example|.env.headroom.example) : ;;  # 템플릿은 허용
  .env|.env.*) deny "시크릿 파일($BASE)은 읽기/쓰기 금지" ".env.example을 참조하라" ;;
  *.pem|*.key) deny "개인키($BASE) 접근 금지" "키는 사람이 직접 관리한다" ;;
  credentials*|secrets*) deny "자격증명 파일($BASE) 접근 금지" "필요 값은 사람에게 요청하라" ;;
  terraform.tfstate|terraform.tfstate.*) deny "tfstate 접근 금지" "해당 없음(이 저장소는 IaC 없음)" ;;
esac

# 2) 쓰기 전용 차단 대상
if [ "$MODE" = "write" ]; then
  case "$NORM" in
    */src/test/resources/golden/*|*/src/main/resources/golden/*)
      deny "골든 픽스처는 직접 수정 금지 (패러티 규율 5조: 불일치 시 Kotlin 구현을 고친다)" \
           "갱신이 필요하면 tools/php-golden/ 실 캡처로만" ;;
    legacy/*|*/legacy/*)
      deny "legacy/ 원작(PHP grand truth)은 수정 금지" "오라클은 읽기 전용이다" ;;
  esac
fi

# 3) .mcp.json 토큰 패턴 스캔 — un-ignore 보상 가드레일 (ADR-LITE-007)
if [ "$MODE" = "write" ] && [ "$BASE" = ".mcp.json" ]; then
  # 훅 모드: 기입될 content/new_string에서 스캔 (위 python이 채움)
  if [ -n "${MCP_TOKEN_PATTERN:-}" ]; then
    deny ".mcp.json에 토큰/시크릿 패턴(${MCP_TOKEN_PATTERN}) 기입 금지 — 이 파일은 커밋된다" \
         "값은 env 참조로, 원격 MCP 인증은 대화형 OAuth로 (docs/agent/tool-capabilities.md)"
  fi
  # 수동 모드: 디스크의 파일을 스캔
  if [ $# -ge 1 ] && [ -f "$FILE_PATH" ]; then
    HIT="$(python3 -c '
import re,sys
text=open(sys.argv[1],encoding="utf-8",errors="replace").read()
m=re.search(r"(sk-ant-[\w-]{8,}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[\w]{20,}|xox[baprs]-[\w-]{10,}|AKIA[0-9A-Z]{16}|glpat-[\w-]{20,}|sntrys_[\w-]{10,}|eyJ[\w-]{20,}\.[\w-]{10,}\.[\w-]{10,})",text)
if m: print(m.group(1)[:10]+"…"); sys.exit(0)
m2=re.search(r"\"(token|apiKey|api_key|password|secret|authorization)\"\s*:\s*\"(?!\$\{)[^\"]+\"",text,re.I)
if m2: print("필드 "+m2.group(1))
' "$FILE_PATH")"
    [ -n "$HIT" ] && deny ".mcp.json에서 토큰/시크릿 패턴(${HIT}) 검출 — 이 파일은 커밋된다" \
         "값은 env 참조로, 원격 MCP 인증은 대화형 OAuth로 (docs/agent/tool-capabilities.md)"
  fi
fi

exit 0
