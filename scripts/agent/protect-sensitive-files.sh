#!/usr/bin/env bash
# Protect secrets and read-only parity truth sources for Claude and Codex hooks.
set -u

FILE_PATHS=""
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
direct_path = tool_input.get("file_path") or tool_input.get("path") or ""
if isinstance(direct_path, str) and direct_path:
    paths.append(direct_path)

command = tool_input.get("command") or ""
if isinstance(command, str):
    paths.extend(re.findall(r"^\*\*\* (?:Add|Update|Delete) File: (.+)$", command, re.M))
    paths.extend(re.findall(r"^\*\*\* Move to: (.+)$", command, re.M))
paths = list(dict.fromkeys(paths))
paths = list(dict.fromkeys(path for item in paths for path in (item, os.path.realpath(item))))

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
    case "$LOWER" in
      */src/test/resources/golden/*|*/src/main/resources/golden/*)
        deny "골든 픽스처는 직접 수정 금지 (패러티 규율 5조)" \
          "갱신이 필요하면 tools/php-golden/ 실 캡처로만"
        ;;
      legacy/*|*/legacy/*)
        deny "legacy/ 원작(PHP grand truth)은 수정 금지" "오라클은 읽기 전용이다"
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
