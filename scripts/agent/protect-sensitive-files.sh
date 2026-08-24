#!/usr/bin/env bash
set -u

FILE_PATHS=""
DELETE_PATHS=""
MOVE_PAIRS=""
MODE="write"
TOOL_NAME=""
MCP_TOKEN_PATTERN=""
PARSE_ERROR=0

deny() {
  echo "BLOCKED: $1" >&2
  echo "안전한 대안: $2" >&2
  exit 2
}

if [ $# -ge 1 ]; then
  FILE_PATHS="$1"
  MODE="${2:-write}"
  case "$MODE" in
    read|write) : ;;
    *) deny "알 수 없는 접근 모드($MODE)" "read 또는 write만 사용하라" ;;
  esac
else
  INPUT="$(cat)"
  eval "$(printf '%s' "$INPUT" | python3 -c '
import json
import os
import re
import shlex
import sys

try:
    payload = json.load(sys.stdin)
except Exception:
    print("PARSE_ERROR=1")
    sys.exit(0)

tool = payload.get("tool_name", "")
tool_input = payload.get("tool_input") or {}
paths = []
delete_paths = []
move_pairs = []
direct_path = tool_input.get("file_path") or tool_input.get("path") or ""
if isinstance(direct_path, str) and direct_path:
    paths.append(direct_path)

command = tool_input.get("command") or ""
if isinstance(command, str):
    paths.extend(re.findall(r"^\*\*\* (?:Add|Update|Delete) File: (.+)$", command, re.M))
    delete_paths.extend(re.findall(r"^\*\*\* Delete File: (.+)$", command, re.M))
    paths.extend(re.findall(r"^\*\*\* Move to: (.+)$", command, re.M))
    current_update = None
    for line in command.splitlines():
        if line.startswith("*** Update File: "):
            current_update = line.removeprefix("*** Update File: ")
        elif line.startswith("*** Move to: ") and current_update:
            destination = line.removeprefix("*** Move to: ")
            move_pairs.extend(
                ((current_update, destination), (os.path.realpath(current_update), os.path.realpath(destination)))
            )
            current_update = None
        elif line.startswith(("*** Add File: ", "*** Delete File: ")):
            current_update = None
paths = list(dict.fromkeys(paths))
paths = list(dict.fromkeys(path for item in paths for path in (item, os.path.realpath(item))))
delete_paths = list(dict.fromkeys(delete_paths))
delete_paths = list(dict.fromkeys(path for item in delete_paths for path in (item, os.path.realpath(item))))

hit = ""
if any(path.replace("\\\\", "/").split("/")[-1] == ".mcp.json" for path in paths):
    text = (
        (tool_input.get("content") or "")
        + "\n"
        + (tool_input.get("new_string") or "")
        + "\n"
        + command
    )
    match = re.search(
        r"(sk-ant-[\w-]{8,}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|"
        r"github_pat_[\w]{20,}|xox[baprs]-[\w-]{10,}|AKIA[0-9A-Z]{16}|"
        r"glpat-[\w-]{20,}|sntrys_[\w-]{10,}|"
        r"eyJ[\w-]{20,}\.[\w-]{10,}\.[\w-]{10,})",
        text,
    )
    if match:
        hit = match.group(1)[:10] + "…"
    else:
        field_match = re.search(
            r"\"(token|apiKey|api_key|password|secret|authorization)\"\s*:\s*\"(?!\$\{)[^\"]+\"",
            text,
            re.I,
        )
        if field_match:
            hit = "필드 " + field_match.group(1)

print("TOOL_NAME=%s" % shlex.quote(tool))
print("FILE_PATHS=%s" % shlex.quote("\n".join(paths)))
print("DELETE_PATHS=%s" % shlex.quote("\n".join(delete_paths)))
print("MOVE_PAIRS=%s" % shlex.quote("\n".join("%s\t%s" % pair for pair in dict.fromkeys(move_pairs))))
print("MCP_TOKEN_PATTERN=%s" % shlex.quote(hit))
')"

  [ "$PARSE_ERROR" -eq 0 ] \
    || deny "훅 입력 JSON을 해석할 수 없음" "유효한 Codex/Claude hook payload를 전달하라"
  case "$TOOL_NAME" in
    Read|Glob|Grep) MODE="read" ;;
    *) MODE="write" ;;
  esac
fi

[ -n "$FILE_PATHS" ] \
  || deny "보호 대상 경로를 훅 입력에서 찾을 수 없음" "file_path/path 또는 Codex apply_patch command를 전달하라"

guard_path() {
  FILE_PATH="$1"
  NORM="$(printf '%s' "$FILE_PATH" | tr '\\' '/')"
  LOWER="$(printf '%s' "$NORM" | tr '[:upper:]' '[:lower:]')"
  BASE="$(basename "$LOWER")"
  IS_TEST_PATH=0
  IS_GOLDEN_PATH=0
  case "$LOWER" in
    */src/test|*/src/test/*|*/test|*/test/*|*/tests|*/tests/*|*/__tests__|*/__tests__/*|*test_*.py|*_test.py|*.test.*|*.spec.*) IS_TEST_PATH=1 ;;
  esac
  case "$NORM" in
    *Test.kt|*Tests.kt|*IT.kt|*Test.java|*Tests.java|*IT.java|*Test.groovy|*Tests.groovy|*IT.groovy) IS_TEST_PATH=1 ;;
  esac
  case "$LOWER" in
    */src/test/resources/golden/*|*/src/main/resources/golden/*|*/src/test/*/golden/*|*/src/test/*goldentest.kt|*/src/test/*goldenit.kt) IS_GOLDEN_PATH=1 ;;
  esac
  IS_TRACKED=0
  git ls-files --error-unmatch -- "$FILE_PATH" >/dev/null 2>&1 && IS_TRACKED=1
  IS_DELETE=0
  MOVE_DEST=""
  while IFS= read -r DELETE_PATH; do
    [ -n "$DELETE_PATH" ] || continue
    DELETE_NORM="$(printf '%s' "$DELETE_PATH" | tr '\\' '/')"
    [ "$NORM" != "$DELETE_NORM" ] || IS_DELETE=1
  done <<EOF
$DELETE_PATHS
EOF
  while IFS="$(printf '\t')" read -r MOVE_SOURCE MOVE_TARGET; do
    [ -n "$MOVE_SOURCE" ] || continue
    MOVE_SOURCE_NORM="$(printf '%s' "$MOVE_SOURCE" | tr '\\' '/')"
    [ "$NORM" != "$MOVE_SOURCE_NORM" ] || MOVE_DEST="$MOVE_TARGET"
  done <<EOF
$MOVE_PAIRS
EOF

  case "$BASE" in
    .env.example|.env.headroom.example) : ;;
    .env|.env.*|settings.local*)
      deny "시크릿 파일($BASE)은 읽기/쓰기 금지" ".env.example 또는 추적 설정을 참조하라"
      ;;
    *.pem|*.key) deny "개인키($BASE) 접근 금지" "키는 사람이 직접 관리한다" ;;
    credentials*|secrets*) deny "자격증명 파일($BASE) 접근 금지" "필요 값은 사람에게 요청하라" ;;
    terraform.tfstate|terraform.tfstate.*)
      deny "tfstate 접근 금지" "해당 없음(이 저장소는 IaC 없음)"
      ;;
  esac

  if [ "$MODE" = "write" ]; then
    if [ -n "$MOVE_DEST" ]; then
      MOVE_DEST_LOWER="$(printf '%s' "$MOVE_DEST" | tr '\\' '/' | tr '[:upper:]' '[:lower:]')"
      MOVE_DEST_NORM="$(printf '%s' "$MOVE_DEST" | tr '\\' '/')"
      MOVE_DEST_IS_TEST=0
      case "$MOVE_DEST_LOWER" in
        */src/test|*/src/test/*|*/test|*/test/*|*/tests|*/tests/*|*/__tests__|*/__tests__/*|*test_*.py|*_test.py|*.test.*|*.spec.*) MOVE_DEST_IS_TEST=1 ;;
      esac
      case "$MOVE_DEST_NORM" in
        *Test.kt|*Tests.kt|*IT.kt|*Test.java|*Tests.java|*IT.java|*Test.groovy|*Tests.groovy|*IT.groovy) MOVE_DEST_IS_TEST=1 ;;
      esac
      MOVE_DEST_IS_GOLDEN=0
      case "$MOVE_DEST_LOWER" in
        */src/test/resources/golden/*|*/src/main/resources/golden/*|*/src/test/*/golden/*|*/src/test/*goldentest.kt|*/src/test/*goldenit.kt) MOVE_DEST_IS_GOLDEN=1 ;;
      esac
      if [ "$IS_TRACKED" -eq 1 ] && [ "$IS_GOLDEN_PATH" -eq 1 ] && [ "$MOVE_DEST_IS_GOLDEN" -ne 1 ]; then
        deny "기존 골든을 골든 보호 영역 밖으로 이동 금지" "골든 보호 영역 안에서 이름을 바꾸고 경로별 회귀 증거를 남겨라"
      elif [ "$IS_TRACKED" -eq 1 ] && [ "$IS_TEST_PATH" -eq 1 ] && [ "$MOVE_DEST_IS_TEST" -ne 1 ]; then
        deny "기존 추적 테스트를 테스트 영역 밖으로 이동 금지" "테스트 영역 안에서 이름을 바꾸고 회귀 기준선을 보존하라"
      fi
    fi
    if [ "$IS_DELETE" -eq 1 ]; then
      if [ "$IS_TRACKED" -eq 1 ] && [ "$IS_TEST_PATH" -eq 1 ]; then
        deny "기존 추적 테스트 삭제 금지" "승인된 제품 변경은 테스트를 보존하고 명시적 이유·회귀 증거와 함께 기대값만 갱신하라"
      fi
    fi
    case "$LOWER" in
      */src/test/resources/golden/*|*/src/main/resources/golden/*|*/src/test/*/golden/*|*/src/test/*goldentest.kt|*/src/test/*goldenit.kt)
        echo "NOTICE: 기존 골든은 동결 회귀 기준선이다. 승인된 제품 변경일 때만 명시적 변경 이유와 회귀 증거로 기대값을 갱신하라. strict review는 변경 경로별 Golden path: / Golden change reason: / 실행한 Regression command: / Regression evidence: PASS / Critique: CLEARED를 요구한다." >&2
        ;;
      legacy/*|*/legacy/*)
        deny "legacy/ 역사 참고 자료는 수정 금지" "참고 자료는 읽기 전용이다"
        ;;
    esac
  fi

  if [ "$MODE" = "write" ] && [ "$BASE" = ".mcp.json" ]; then
    if [ -n "${MCP_TOKEN_PATTERN:-}" ]; then
      deny ".mcp.json에 토큰/시크릿 패턴(${MCP_TOKEN_PATTERN}) 기입 금지" \
        "값은 env 참조로, 원격 MCP 인증은 대화형 OAuth로"
    fi
    if [ -f "$FILE_PATH" ]; then
      HIT="$(python3 -c '
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
match = re.search(
    r"(sk-ant-[\w-]{8,}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|"
    r"github_pat_[\w]{20,}|xox[baprs]-[\w-]{10,}|AKIA[0-9A-Z]{16}|"
    r"glpat-[\w-]{20,}|sntrys_[\w-]{10,}|"
    r"eyJ[\w-]{20,}\.[\w-]{10,}\.[\w-]{10,})",
    text,
)
if match:
    print(match.group(1)[:10] + "…")
    sys.exit(0)
field_match = re.search(
    r"\"(token|apiKey|api_key|password|secret|authorization)\"\s*:\s*\"(?!\$\{)[^\"]+\"",
    text,
    re.I,
)
if field_match:
    print("필드 " + field_match.group(1))
' "$FILE_PATH")"
      [ -z "$HIT" ] \
        || deny ".mcp.json에서 토큰/시크릿 패턴(${HIT}) 검출" \
          "값은 env 참조로, 원격 MCP 인증은 대화형 OAuth로"
    fi
  fi
}

while IFS= read -r FILE_PATH; do
  [ -n "$FILE_PATH" ] && guard_path "$FILE_PATH"
done <<EOF
$FILE_PATHS
EOF

exit 0
