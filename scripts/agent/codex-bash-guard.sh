#!/usr/bin/env bash
# Best-effort guard for the simple Bash calls that Codex hooks can intercept.
set -euo pipefail

python3 -c '
import json
import re
import sys

def block(reason: str) -> None:
    print(f"BLOCKED: {reason}", file=sys.stderr)
    print("안전한 대안: 추적 파일과 읽기 전용 오라클 명령만 사용하라", file=sys.stderr)
    raise SystemExit(2)

try:
    payload = json.load(sys.stdin)
except Exception:
    block("Bash 훅 입력 JSON을 해석할 수 없음")

if payload.get("tool_name") != "Bash":
    block("Bash 훅에 잘못된 tool_name이 전달됨")
command = (payload.get("tool_input") or {}).get("command")
if not isinstance(command, str) or not command.strip():
    block("Bash command가 비어 있음")

token_pattern = re.compile(
    r"sk-ant-[\w-]{8,}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|"
    r"github_pat_[\w]{20,}|xox[baprs]-[\w-]{10,}|AKIA[0-9A-Z]{16}|"
    r"glpat-[\w-]{20,}|sntrys_[\w-]{10,}|"
    r"eyJ[\w-]{20,}\.[\w-]{10,}\.[\w-]{10,}"
)
if token_pattern.search(command):
    block("shell command에 토큰/시크릿 패턴이 포함됨")

secret_scan = re.sub(r"(?i)\.env(?:\.headroom)?\.example", "", command)
secret_path = re.compile(
    r"(?i)(?:^|[/\s\x22\x27=])(?:\.env(?:\.[\w.-]+)?|settings\.local[^/\s]*|"
    r"[^/\s]*\.(?:pem|key)|credentials[^/\s]*|secrets[^/\s]*|"
    r"terraform\.tfstate(?:\.[^/\s]*)?)(?:$|[/\s\x22\x27=:])"
)
if secret_path.search(secret_scan):
    block("shell command가 보호된 시크릿 경로를 참조함")

protected = r"(?:\.?/?legacy(?:/|$)|[^\s\x22\x27]*/legacy/[^\s\x22\x27]*|[^\s\x22\x27]*/src/(?:test|main)/resources/golden/[^\s\x22\x27]*)"
redirection = re.compile(r"(?:>|>>)\s*[\x22\x27]?" + protected, re.I)
tee_write = re.compile(r"\btee\b[^;&|]*" + protected, re.I)
mutator = re.compile(
    r"(?:^|[;&|]\s*)(?:sudo\s+)?(?:rm|mv|cp|touch|mkdir|install|truncate|chmod|chown|ln|apply_patch|"
    r"git\s+(?:checkout|restore|clean)|sed\b[^;&|]*\s-i\b|perl\b[^;&|]*\s-pi\b)"
    r"[^;&|]*" + protected,
    re.I,
)
if redirection.search(command) or tee_write.search(command) or mutator.search(command):
    block("shell command가 legacy 또는 golden 경로를 수정하려 함")
'
