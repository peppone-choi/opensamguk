#!/usr/bin/env bash
# Best-effort guard for the simple Bash calls that Codex hooks can intercept.
set -euo pipefail

python3 -c '
import json
import os
import re
import shlex
import subprocess
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

def has_active_shell_dynamic(shell_text: str) -> bool:
    single_quoted = False
    double_quoted = False
    escaped = False
    cursor = 0
    while cursor < len(shell_text):
        char = shell_text[cursor]
        if escaped:
            escaped = False
            cursor += 1
            continue
        if char == "\\" and not single_quoted:
            escaped = True
            cursor += 1
            continue
        if char == "\x27" and not double_quoted:
            single_quoted = not single_quoted
            cursor += 1
            continue
        if char == "\x22" and not single_quoted:
            double_quoted = not double_quoted
            cursor += 1
            continue
        if not single_quoted:
            if char == "`" or shell_text.startswith("$(", cursor):
                return True
            if not double_quoted and shell_text.startswith(("<(", ">("), cursor):
                return True
        cursor += 1
    return False

safe_java_home_assignment = re.compile(
    r"(?<!\S)JAVA_HOME=\$\(/usr/libexec/java_home[ \t]+-v[ \t]+21\)(?=\s|$)"
)
guard_command = safe_java_home_assignment.sub("JAVA_HOME=/__codex_java_home_21__", command)

if has_active_shell_dynamic(guard_command):
    block("동적 shell substitution을 안전하게 해석할 수 없음")

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

def is_test_path(path: str) -> bool:
    original_name = path.replace("\\", "/").rsplit("/", 1)[-1]
    normalized = "/" + path.replace("\\", "/").lower().lstrip("/")
    name = normalized.rsplit("/", 1)[-1]
    return (
        is_test_directory(path)
        or re.search(r"(?:^test_.*|.*_test)\.py$", name) is not None
        or re.search(r"(?:^|[._-])(?:test|spec)\.[^.]+(?:\.[^.]+)?$", name) is not None
        or re.search(r"(?:Test|Tests|IT)\.(?:kt|java|groovy)$", original_name) is not None
    )

def is_test_directory(path: str) -> bool:
    normalized = "/" + path.replace("\\", "/").lower().strip("/") + "/"
    return any(segment in normalized for segment in ("/src/test/", "/test/", "/tests/", "/__tests__/"))

def is_golden_path(path: str) -> bool:
    normalized = "/" + path.replace("\\", "/").lower().lstrip("/")
    return (
        "/resources/golden/" in normalized
        or ("/src/test/" in normalized and "/golden/" in normalized)
        or ("/src/test/" in normalized and normalized.endswith(("goldentest.kt", "goldenit.kt")))
    )

def tracked_protected_paths_at_or_below(path: str) -> list[str]:
    normalized = path.replace("\\", "/").strip("/")
    if normalized == ".":
        normalized = ""
    return [candidate for candidate in protected_baseline if candidate == normalized or candidate.startswith(normalized + "/") or not normalized]

repo_root = subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], check=True, text=True, stdout=subprocess.PIPE
).stdout.strip()
repo_root_real = os.path.realpath(repo_root)
head_paths = subprocess.run(
    ["git", "ls-tree", "-r", "--name-only", "HEAD"], check=False, text=True, stdout=subprocess.PIPE
).stdout.splitlines()
index_paths = subprocess.run(
    ["git", "ls-files"], check=True, text=True, stdout=subprocess.PIPE
).stdout.splitlines()
protected_baseline = frozenset(
    path for path in {*head_paths, *index_paths} if is_test_path(path) or is_golden_path(path)
)

def absolute_path(path: str, base: str) -> str:
    return os.path.realpath(path if os.path.isabs(path) else os.path.join(base, path))

def is_inside_repo(path: str) -> bool:
    try:
        return os.path.commonpath((repo_root_real, path)) == repo_root_real
    except ValueError:
        return False

def resolve(path: str, base: str) -> str:
    absolute = absolute_path(path, base)
    if not is_inside_repo(absolute):
        block("현재 worktree 밖의 mutation 경로를 안전하게 해석할 수 없음")
    return os.path.relpath(absolute, repo_root).replace("\\", "/")

def is_protected_write_destination(path: str, base: str) -> bool:
    absolute = absolute_path(path, base)
    if not is_inside_repo(absolute):
        return False
    relative = os.path.relpath(absolute, repo_root).replace("\\", "/")
    return (
        relative == "legacy"
        or relative.startswith("legacy/")
        or "/legacy/" in f"/{relative}/"
        or is_golden_path(relative)
        or bool(tracked_protected_paths_at_or_below(relative))
    )

class RedirectionToken(str):
    """A shell token containing an unquoted redirection operator."""

def check_output_redirections(args: list[str], base: str) -> list[str]:
    remaining: list[str] = []
    cursor = 0
    while cursor < len(args):
        token = args[cursor]
        if not isinstance(token, RedirectionToken):
            remaining.append(token)
            cursor += 1
            continue
        descriptor = r"(?:\d+|\{[A-Za-z_][A-Za-z0-9_]*\})?"
        match = re.fullmatch(rf"({descriptor}(?:>&|<>|>>|>\||>)|&(?:>>|>))(.*)", token)
        if match is None:
            remaining.append(token)
            cursor += 1
            continue
        operator = match.group(1)
        destination = match.group(2)
        if not destination:
            cursor += 1
            if cursor >= len(args):
                block("output redirection destination을 안전하게 해석할 수 없음")
            destination = args[cursor]
        if operator.endswith(">&") and re.fullmatch(r"(?:\d+|-)", destination):
            cursor += 1
            continue
        if not destination or destination.startswith((">", "<")) or has_expansion(destination):
            block("output redirection destination을 안전하게 해석할 수 없음")
        if is_protected_write_destination(destination, base):
            block("output redirection으로 보호된 test/golden/legacy 경로 쓰기 금지")
        cursor += 1
    return remaining

def text_references_protected(words: list[str], base: str) -> bool:
    for word in words:
        for candidate in re.findall(r"[\w@+./-]+", word):
            if candidate in {"..", "/"}:
                continue
            absolute = absolute_path(candidate, base)
            if not is_inside_repo(absolute):
                if "/" in candidate or candidate.startswith("."):
                    return True
                continue
            relative = os.path.relpath(absolute, repo_root).replace("\\", "/")
            if tracked_protected_paths_at_or_below(relative):
                return True
    return False

def check_move(sources: list[str], destination: str, base: str) -> None:
    resolved_destination = resolve(destination, base)
    destination_is_directory = os.path.isdir(absolute_path(destination, base))
    for raw_source in sources:
        source = resolve(raw_source, base)
        source_is_single_file = source in protected_baseline and os.path.isfile(absolute_path(raw_source, base))
        destination_base = resolved_destination.rstrip("/")
        source_name = source.rsplit("/", 1)[-1]
        effective_destination = (
            f"{destination_base}/{source_name}"
            if destination_is_directory
            else resolved_destination
        )
        protected_sources = tracked_protected_paths_at_or_below(source)
        if not protected_sources:
            continue
        if any(is_golden_path(candidate) for candidate in protected_sources) and not is_golden_path(effective_destination):
            block("기존 골든을 골든 보호 영역 밖으로 이동 금지")
        if (
            any(not is_golden_path(candidate) for candidate in protected_sources)
            and not (
                is_test_directory(effective_destination)
                or (source_is_single_file and is_test_path(effective_destination))
            )
        ):
            block("기존 추적 테스트를 테스트 디렉터리 밖으로 이동 금지")

def parse_move_operands(args: list[str]) -> tuple[list[str], str] | None:
    operands: list[str] = []
    target_directory: str | None = None
    cursor = 0
    while cursor < len(args):
        word = args[cursor]
        if word == "--":
            operands.extend(args[cursor + 1:])
            break
        if word in {"-t", "--target-directory"}:
            if cursor + 1 >= len(args):
                block("mv target-directory operand를 안전하게 해석할 수 없음")
            target_directory = args[cursor + 1]
            cursor += 2
            continue
        if word.startswith("--target-directory="):
            target_directory = word.split("=", 1)[1]
            if not target_directory:
                block("mv target-directory operand를 안전하게 해석할 수 없음")
            cursor += 1
            continue
        if word.startswith("-t") and word != "-t":
            target_directory = word[2:]
            if not target_directory:
                block("mv target-directory operand를 안전하게 해석할 수 없음")
            cursor += 1
            continue
        if word in {"-S", "--suffix"}:
            if cursor + 1 >= len(args):
                block("mv suffix option operand를 안전하게 해석할 수 없음")
            cursor += 2
            continue
        if word in {
            "-f", "--force", "-i", "--interactive", "-n", "--no-clobber", "-v", "--verbose",
            "-T", "--no-target-directory", "--strip-trailing-slashes", "-b", "--backup", "-k",
        } or word.startswith(("--suffix=", "--backup=")):
            cursor += 1
            continue
        if word.startswith("-"):
            block(f"알 수 없는 mv option: {word}")
        operands.append(word)
        cursor += 1
    if target_directory is not None:
        return (operands, target_directory) if operands else None
    return (operands[:-1], operands[-1]) if len(operands) >= 2 else None

def parse_name_status(raw: bytes) -> list[tuple[str, ...]]:
    tokens = raw.split(b"\0")
    if tokens and tokens[-1] == b"":
        tokens.pop()
    entries: list[tuple[str, ...]] = []
    cursor = 0
    while cursor < len(tokens):
        status = tokens[cursor].decode("utf-8", "surrogateescape")
        cursor += 1
        width = 2 if status.startswith(("R", "C")) else 1
        if cursor + width > len(tokens):
            block("git name-status 결과를 안전하게 해석할 수 없음")
        paths = tuple(token.decode("utf-8", "surrogateescape") for token in tokens[cursor:cursor + width])
        entries.append((status, *paths))
        cursor += width
    return entries

def block_protected_deletions(entries: list[tuple[str, ...]], action: str) -> None:
    for entry in entries:
        status = entry[0]
        if status == "D" and entry[1] in protected_baseline:
            block(f"git {action}이 기존 추적 테스트/골든 삭제를 반영할 수 있음")
        if status.startswith("R") and entry[1] in protected_baseline:
            source, destination = entry[1], entry[2]
            if is_golden_path(source) and not is_golden_path(destination):
                block(f"git {action}이 기존 골든 rename-out을 반영할 수 있음")
            if not is_golden_path(source) and not (is_test_directory(destination) or is_test_path(destination)):
                block(f"git {action}이 기존 추적 테스트 rename-out을 반영할 수 있음")

def command_name(token: str) -> str:
    return os.path.basename(token)

def interpreter_family(command: str) -> str | None:
    versioned_families = (
        (r"python(?:\d+(?:\.\d+)*)?", "python"),
        (r"pypy(?:\d+(?:\.\d+)*)?", "pypy"),
        (r"perl(?:\d+(?:\.\d+)*)?", "perl"),
        (r"ruby(?:\d+(?:\.\d+)*)?", "ruby"),
        (r"node(?:js)?(?:\d+(?:\.\d+)*)?", "node"),
        (r"php(?:\d+(?:\.\d+)*)?", "php"),
        (r"lua(?:\d+(?:\.\d+)*)?", "lua"),
    )
    return next((family for pattern, family in versioned_families if re.fullmatch(pattern, command)), None)

INTERPRETER_ENVIRONMENT_PREFIXES = {
    "python": "PYTHON", "pypy": "PYTHON", "php": "PHP",
    "ruby": "RUBY", "node": "NODE", "lua": "LUA",
}

def has_interpreter_environment(keys: set[str], family: str | None = None) -> bool:
    prefixes = (
        {INTERPRETER_ENVIRONMENT_PREFIXES[family]}
        if family in INTERPRETER_ENVIRONMENT_PREFIXES
        else set(INTERPRETER_ENVIRONMENT_PREFIXES.values())
    )
    return any(key.startswith(prefix) for key in keys for prefix in prefixes)

def has_expansion(token: str) -> bool:
    return any(char in token for char in ("$", "{", "}", "*", "?", "[", "]"))

def split_segments(shell_command: str) -> list[list[str]]:
    normalized_chars: list[str] = []
    single_quoted = False
    double_quoted = False
    escaped = False
    sentinel_prefix = "__CODEX_TYPED_REDIR__"
    while sentinel_prefix in shell_command:
        sentinel_prefix += "_"
    redirection_sentinels = {
        operator: f"{sentinel_prefix}{index}__"
        for index, operator in enumerate(("&>>", "&>", ">|", ">>", ">&", "<>", ">"))
    }
    cursor = 0
    while cursor < len(shell_command):
        char = shell_command[cursor]
        if escaped:
            normalized_chars.append(char)
            escaped = False
            cursor += 1
            continue
        if char == "\\" and not single_quoted:
            normalized_chars.append(char)
            escaped = True
            cursor += 1
            continue
        if char == "\x27" and not double_quoted:
            single_quoted = not single_quoted
        elif char == "\x22" and not single_quoted:
            double_quoted = not double_quoted
        if not single_quoted and not double_quoted:
            matched_operator = next(
                (operator for operator in redirection_sentinels if shell_command.startswith(operator, cursor)),
                None,
            )
            if matched_operator:
                normalized_chars.append(redirection_sentinels[matched_operator])
                cursor += len(matched_operator)
                continue
        normalized_chars.append(";" if char == "\n" and not single_quoted and not double_quoted else char)
        cursor += 1
    try:
        lexer = shlex.shlex("".join(normalized_chars), posix=True, punctuation_chars=";&|()")
        lexer.whitespace_split = True
        lexer.commenters = ""
        words = []
        for word in lexer:
            marked = [
                (word.index(sentinel), operator, sentinel, word.count(sentinel))
                for operator, sentinel in redirection_sentinels.items()
                if sentinel in word
            ]
            if not marked:
                words.append(word)
                continue
            if sum(count for _, _, _, count in marked) != 1:
                block("multiple attached redirections을 안전하게 해석할 수 없음")
            position, operator, sentinel, _ = marked[0]
            before = word[:position]
            after = word[position + len(sentinel):]
            descriptor = re.fullmatch(r"(?:\d+|\{[A-Za-z_][A-Za-z0-9_]*\})", before)
            if before and descriptor is None:
                words.append(before)
                before = ""
            words.append(RedirectionToken(before + operator + after))
    except ValueError:
        block("shell command 인용부호를 해석할 수 없음")
    segments: list[list[str]] = [[]]
    for word in words:
        if word and all(char in ";&|()" for char in word):
            segments.append([])
        else:
            segments[-1].append(word)
    return [segment for segment in segments if segment]

def wrapper_cwd(path: str, base: str, wrapper: str) -> str:
    if has_expansion(path):
        block(f"{wrapper} chdir 경로를 안전하게 해석할 수 없음")
    candidate = os.path.normpath(path if os.path.isabs(path) else os.path.join(base, path))
    if not os.path.isdir(candidate):
        block(f"{wrapper} chdir 경로를 안전하게 해석할 수 없음")
    return candidate

def unwrap_command(segment: list[str], start_cwd: str) -> tuple[str, list[str], str, str, dict[str, str]]:
    cursor = 0
    effective_cwd = start_cwd
    environment_assignments: dict[str, str] = {}
    while cursor < len(segment) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", segment[cursor]):
        key, value = segment[cursor].split("=", 1)
        environment_assignments[key] = value
        cursor += 1
    while cursor < len(segment):
        wrapper_token = segment[cursor]
        if "/" in wrapper_token:
            break
        wrapper = command_name(wrapper_token)
        if wrapper == "env":
            cursor += 1
            while cursor < len(segment):
                option = segment[cursor]
                if option == "--":
                    cursor += 1
                    while cursor < len(segment) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", segment[cursor]):
                        key, value = segment[cursor].split("=", 1)
                        environment_assignments[key] = value
                        cursor += 1
                    break
                if option in {"-u", "--unset", "-C", "--chdir"}:
                    if cursor + 1 >= len(segment):
                        block("env option operand를 안전하게 해석할 수 없음")
                    if option in {"-C", "--chdir"}:
                        effective_cwd = wrapper_cwd(segment[cursor + 1], effective_cwd, "env")
                    cursor += 2
                    continue
                if option in {"-a", "--argv0"}:
                    if cursor + 1 >= len(segment) or has_expansion(segment[cursor + 1]):
                        block("env argv0 operand를 안전하게 해석할 수 없음")
                    cursor += 2
                    continue
                if option.startswith("--chdir="):
                    effective_cwd = wrapper_cwd(option.split("=", 1)[1], effective_cwd, "env")
                    cursor += 1
                    continue
                if option.startswith("--argv0="):
                    if not option.split("=", 1)[1] or has_expansion(option.split("=", 1)[1]):
                        block("env argv0 operand를 안전하게 해석할 수 없음")
                    cursor += 1
                    continue
                if option.startswith("--unset=") or option in {"-i", "--ignore-environment", "-0", "--null"}:
                    cursor += 1
                    continue
                if option.startswith("-"):
                    block("알 수 없는 env wrapper option")
                if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", option):
                    key, value = option.split("=", 1)
                    environment_assignments[key] = value
                    cursor += 1
                    continue
                break
            continue
        if wrapper == "sudo":
            cursor += 1
            while cursor < len(segment):
                option = segment[cursor]
                if option == "--":
                    cursor += 1
                    break
                if option in {"-u", "-g", "-h", "-p", "-C", "-T", "-R", "-D", "--user", "--group", "--host", "--prompt", "--chdir", "--chroot", "--role", "--type"}:
                    if cursor + 1 >= len(segment):
                        block("sudo option operand를 안전하게 해석할 수 없음")
                    if option in {"-D", "--chdir"}:
                        effective_cwd = wrapper_cwd(segment[cursor + 1], effective_cwd, "sudo")
                    cursor += 2
                    continue
                if option.startswith("--chdir="):
                    effective_cwd = wrapper_cwd(option.split("=", 1)[1], effective_cwd, "sudo")
                    cursor += 1
                    continue
                if option.startswith(("--user=", "--group=", "--host=", "--prompt=", "--chroot=", "--role=", "--type=")):
                    cursor += 1
                    continue
                if option in {"-n", "-E", "-H", "-S", "-k", "-K", "-b", "--non-interactive", "--preserve-env", "--set-home", "--stdin", "--background"}:
                    cursor += 1
                    continue
                if option.startswith("-"):
                    block("알 수 없는 sudo wrapper option")
                break
            continue
        if wrapper == "command":
            cursor += 1
            while cursor < len(segment) and segment[cursor].startswith("-"):
                option = segment[cursor]
                if option == "--":
                    cursor += 1
                    break
                if option == "-p":
                    cursor += 1
                    continue
                if option in {"-v", "-V"}:
                    return "", [], effective_cwd, "", environment_assignments
                block("알 수 없는 command wrapper option")
            continue
        if wrapper == "exec":
            cursor += 1
            while cursor < len(segment) and segment[cursor].startswith("-"):
                option = segment[cursor]
                if option == "--":
                    cursor += 1
                    break
                if option in {"-c", "-l"}:
                    cursor += 1
                    continue
                if option == "-a":
                    if cursor + 1 >= len(segment):
                        block("exec -a operand를 안전하게 해석할 수 없음")
                    cursor += 2
                    continue
                block("알 수 없는 exec wrapper option")
            continue
        if wrapper == "nice":
            cursor += 1
            while cursor < len(segment) and segment[cursor].startswith("-"):
                option = segment[cursor]
                if option == "--":
                    cursor += 1
                    break
                if option in {"-n", "--adjustment"}:
                    if cursor + 1 >= len(segment):
                        block("nice adjustment operand를 안전하게 해석할 수 없음")
                    cursor += 2
                    continue
                if option.startswith("--adjustment=") or re.fullmatch(r"-\d+", option):
                    cursor += 1
                    continue
                block("알 수 없는 nice wrapper option")
            continue
        if wrapper == "time":
            cursor += 1
            while cursor < len(segment) and segment[cursor].startswith("-"):
                option = segment[cursor]
                if option == "--":
                    cursor += 1
                    break
                if option == "-p":
                    cursor += 1
                    continue
                block("알 수 없는 time wrapper option")
            continue
        if wrapper == "timeout":
            cursor += 1
            while cursor < len(segment) and segment[cursor].startswith("-"):
                option = segment[cursor]
                if option == "--":
                    cursor += 1
                    break
                if option in {"-k", "--kill-after", "-s", "--signal"}:
                    if cursor + 1 >= len(segment):
                        block("timeout option operand를 안전하게 해석할 수 없음")
                    cursor += 2
                    continue
                if option.startswith(("--kill-after=", "--signal=")) or option in {"--foreground", "--preserve-status", "--verbose"}:
                    cursor += 1
                    continue
                block("알 수 없는 timeout wrapper option")
            if cursor >= len(segment) or segment[cursor].startswith("-"):
                block("timeout duration을 안전하게 해석할 수 없음")
            cursor += 1
            continue
        if wrapper in {"builtin", "nohup"}:
            cursor += 1
            if cursor < len(segment) and segment[cursor] == "--":
                cursor += 1
            elif cursor < len(segment) and segment[cursor].startswith("-"):
                block(f"알 수 없는 {wrapper} wrapper option")
            continue
        break
    if cursor >= len(segment):
        return "", [], effective_cwd, "", environment_assignments
    executable = segment[cursor]
    return command_name(executable), segment[cursor + 1:], effective_cwd, executable, environment_assignments

def analyze_git(args: list[str], cwd: str) -> None:
    if len(args) == 1 and (args[0] in {"--version", "--help", "--exec-path"} or args[0].startswith("--exec-path=")):
        return
    git_base = cwd
    git_context_options: list[str] = ["-C", git_base]
    canonical_git_dir = subprocess.run(
        ["git", "rev-parse", "--absolute-git-dir"], check=True, text=True, stdout=subprocess.PIPE
    ).stdout.strip()
    alternate_repository = False
    cursor = 0
    while cursor < len(args) and args[cursor].startswith("-"):
        option = args[cursor]
        if option == "--":
            cursor += 1
            break
        if option == "-C":
            if cursor + 1 >= len(args) or has_expansion(args[cursor + 1]):
                block("git -C operand를 안전하게 해석할 수 없음")
            git_base = wrapper_cwd(args[cursor + 1], git_base, "git -C")
            git_context_options.extend(("-C", git_base))
            cursor += 2
            continue
        if option.startswith("-C") and option != "-C":
            directory = option[2:]
            git_base = wrapper_cwd(directory, git_base, "git -C")
            git_context_options.extend(("-C", git_base))
            cursor += 1
            continue
        if option in {"-c", "--config-env"}:
            block(f"git {option} runtime configuration은 보호 경계에서 허용되지 않음")
        if option in {"--git-dir", "--work-tree", "--namespace", "--super-prefix"}:
            if cursor + 1 >= len(args) or has_expansion(args[cursor + 1]):
                block(f"git {option} operand를 안전하게 해석할 수 없음")
            option_value = args[cursor + 1]
            if option == "-c" and option_value.lower().startswith("alias."):
                block("동적 git alias를 안전하게 해석할 수 없음")
            if option == "--config-env" and option_value.lower().startswith("alias."):
                block("환경 기반 git alias를 안전하게 해석할 수 없음")
            if option == "--git-dir":
                candidate = os.path.normpath(option_value if os.path.isabs(option_value) else os.path.join(git_base, option_value))
                alternate_repository = alternate_repository or candidate != canonical_git_dir
                git_context_options.append(f"--git-dir={candidate}")
            if option == "--work-tree":
                candidate = os.path.normpath(option_value if os.path.isabs(option_value) else os.path.join(git_base, option_value))
                alternate_repository = alternate_repository or candidate != repo_root
                git_context_options.append(f"--work-tree={candidate}")
            if option in {"--namespace", "--super-prefix"}:
                alternate_repository = True
            cursor += 2
            continue
        if option.startswith("--config-env="):
            block("git --config-env runtime configuration은 보호 경계에서 허용되지 않음")
        if option.startswith(("--git-dir=", "--work-tree=", "--namespace=", "--super-prefix=")):
            option_name, option_value = option.split("=", 1)
            if not option_value or has_expansion(option_value):
                block(f"git {option_name} operand를 안전하게 해석할 수 없음")
            if option_name == "--config-env" and option_value.lower().startswith("alias."):
                block("환경 기반 git alias를 안전하게 해석할 수 없음")
            if option_name == "--git-dir":
                candidate = os.path.normpath(option_value if os.path.isabs(option_value) else os.path.join(git_base, option_value))
                alternate_repository = alternate_repository or candidate != canonical_git_dir
                git_context_options.append(f"--git-dir={candidate}")
            if option_name == "--work-tree":
                candidate = os.path.normpath(option_value if os.path.isabs(option_value) else os.path.join(git_base, option_value))
                alternate_repository = alternate_repository or candidate != repo_root
                git_context_options.append(f"--work-tree={candidate}")
            if option_name in {"--namespace", "--super-prefix"}:
                alternate_repository = True
            cursor += 1
            continue
        if option == "--icase-pathspecs":
            block("git --icase-pathspecs는 보호 경로의 대소문자 판정을 바꾸므로 허용하지 않음")
        if option == "--bare":
            alternate_repository = True
            cursor += 1
            continue
        if option in {"-P", "-p", "--no-pager", "--paginate", "--no-replace-objects", "--literal-pathspecs", "--glob-pathspecs", "--noglob-pathspecs"}:
            cursor += 1
            continue
        block("알 수 없는 git global option")
    if cursor >= len(args):
        return
    verb = args[cursor]
    denied_worktree_mutations = {
        "reset", "clean", "switch", "checkout", "restore", "rebase", "merge", "cherry-pick",
        "revert", "am", "apply", "stash", "bisect", "replace", "filter-branch", "gc", "prune",
    }
    if verb in denied_worktree_mutations:
        block(f"git {verb} mutation은 보호된 worktree 보존 경계에서 허용되지 않음")
    mutation_verbs = {"rm", "mv", "update-index"}
    supported_workflow_commands = {"add", "commit", "fetch", "push"}
    if verb in mutation_verbs | supported_workflow_commands:
        context = subprocess.run(
            ["git", *git_context_options, "rev-parse", "--show-toplevel", "--absolute-git-dir"],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        context_lines = context.stdout.splitlines()
        if (
            alternate_repository
            or context.returncode != 0
            or len(context_lines) != 2
            or os.path.realpath(context_lines[0]) != repo_root_real
            or os.path.realpath(context_lines[1]) != os.path.realpath(canonical_git_dir)
        ):
            block("현재 worktree 밖의 git mutation을 안전하게 해석할 수 없음")
    if verb in supported_workflow_commands:
        workflow_flags = {
            "add": {"-A", "--all", "-u", "--update", "-N", "--intent-to-add", "-f", "--force", "--dry-run", "--ignore-errors", "--renormalize", "--verbose", "-v", "--"},
            "commit": {"--amend", "--no-edit", "--allow-empty", "--allow-empty-message", "--no-verify", "--dry-run", "--quiet", "-q", "--verbose", "-v", "--all", "-a", "--signoff", "-s", "--"},
            "fetch": {"--all", "--append", "--atomic", "--dry-run", "--force", "-f", "--keep", "-k", "--multiple", "--no-tags", "--prune", "-p", "--prune-tags", "--quiet", "-q", "--tags", "-t", "--update-head-ok", "--verbose", "-v", "--"},
            "push": {"--all", "--atomic", "--dry-run", "-n", "--follow-tags", "--force", "-f", "--force-if-includes", "--force-with-lease", "--mirror", "--no-verify", "--porcelain", "--prune", "--quiet", "-q", "--set-upstream", "-u", "--signed", "--tags", "--verbose", "-v", "--"},
        }
        workflow_value_options = {
            "commit": {"-m", "--message", "-C", "--reuse-message", "-c", "--reedit-message", "--author", "--date", "--fixup", "--squash", "--trailer"},
            "fetch": {"--depth", "--deepen", "--shallow-since", "--shallow-exclude", "--jobs", "-j", "--refmap", "--server-option", "-o"},
            "push": {"--push-option", "-o"},
        }
        option_args = args[cursor + 1:]
        workflow_operands: list[str] = []
        index = 0
        while index < len(option_args):
            option = option_args[index]
            if option == "--":
                workflow_operands.extend(option_args[index + 1:])
                break
            if not option.startswith("-"):
                workflow_operands.append(option)
                index += 1
                continue
            if option in workflow_flags[verb] or (
                verb == "push" and option.startswith(("--force-with-lease=", "--signed="))
            ):
                index += 1
                continue
            value_options = workflow_value_options.get(verb, set())
            if option in value_options:
                if index + 1 >= len(option_args) or has_expansion(option_args[index + 1]):
                    block(f"git {verb} {option} operand를 안전하게 해석할 수 없음")
                index += 2
                continue
            if any(option.startswith(f"{name}=") for name in value_options if name.startswith("--")):
                index += 1
                continue
            block(f"알 수 없는 git {verb} option")
        if verb in {"add", "commit"} and any(word.startswith(":") for word in workflow_operands):
            block(f"git {verb} pathspec magic operand를 안전하게 해석할 수 없음")

        def prospective_diff(diff_args: list[str]) -> list[tuple[str, ...]]:
            result = subprocess.run(
                ["git", *git_context_options, "diff", "--find-renames", "--name-status", "-z", *diff_args],
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
            if result.returncode != 0:
                block(f"git {verb} prospective diff를 안전하게 계산할 수 없음")
            return parse_name_status(result.stdout)

        if verb == "add":
            diff_args = ["--"] + workflow_operands if workflow_operands else []
            block_protected_deletions(prospective_diff(diff_args), "add")
        elif verb == "commit":
            block_protected_deletions(prospective_diff(["--cached", "HEAD"]), "commit")
            if any(flag in option_args for flag in ("-a", "--all")) or workflow_operands:
                diff_args = ["--"] + workflow_operands if workflow_operands else []
                block_protected_deletions(prospective_diff(diff_args), "commit")
        elif verb in {"fetch", "push"}:
            remote_result = subprocess.run(
                ["git", *git_context_options, "remote"], check=False, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            )
            if remote_result.returncode != 0:
                block(f"git {verb} configured remote를 안전하게 조회할 수 없음")
            configured_remotes = remote_result.stdout.splitlines()
            if verb == "fetch" and "--all" in option_args:
                selected_remotes = configured_remotes
            elif verb == "fetch" and "--multiple" in option_args:
                selected_remotes = []
                for remote_or_group in workflow_operands:
                    if remote_or_group in configured_remotes:
                        selected_remotes.append(remote_or_group)
                        continue
                    group_lines = subprocess.run(
                        ["git", *git_context_options, "config", "--get-all", f"remotes.{remote_or_group}"],
                        check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    ).stdout.splitlines()
                    group_members = [member for line in group_lines for member in shlex.split(line)]
                    if not group_members:
                        block("git fetch --multiple은 configured remote/group 이름만 허용함")
                    selected_remotes.extend(group_members)
            else:
                selected_remotes = workflow_operands[:1]
            if not selected_remotes:
                block(f"git {verb}는 명시적 configured remote 이름이 필요함")
            allowed_url = re.compile(r"^(?:https|ssh|git)://[^\s]+$|^[A-Za-z0-9._-]+@?[A-Za-z0-9.-]+:[^\s]+$")
            for remote in selected_remotes:
                if remote not in configured_remotes or "::" in remote:
                    block(f"git {verb}는 configured remote 이름만 허용함")
                remote_key_suffix = "pushurl" if verb == "push" else "url"
                raw_key = f"remote.{remote}.{remote_key_suffix}"
                raw_urls = subprocess.run(
                    ["git", *git_context_options, "config", "--get-all", raw_key], check=False, text=True,
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                ).stdout.splitlines()
                if verb == "push" and not raw_urls:
                    raw_urls = subprocess.run(
                        ["git", *git_context_options, "config", "--get-all", f"remote.{remote}.url"],
                        check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    ).stdout.splitlines()
                url_command = ["git", *git_context_options, "remote", "get-url", "--all"]
                if verb == "push":
                    url_command.append("--push")
                resolved_urls = subprocess.run(
                    [*url_command, remote], check=False, text=True,
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                ).stdout.splitlines()
                if (
                    not raw_urls
                    or raw_urls != resolved_urls
                    or any("::" in url or allowed_url.fullmatch(url) is None for url in resolved_urls)
                ):
                    block(f"git {verb} remote helper/URL rewrite/non-network URL을 허용하지 않음")
    operands = [word for word in args[cursor + 1:] if not word.startswith("-")]
    if verb in mutation_verbs and any(word.startswith(":") for word in operands):
        block(f"git {verb} pathspec magic operand를 안전하게 해석할 수 없음")
    if verb == "rm":
        if any(word == "--pathspec-from-file" or word.startswith("--pathspec-from-file=") for word in args[cursor + 1:]):
            block("git rm pathspec file 내용을 안전하게 해석할 수 없음")
        if any(tracked_protected_paths_at_or_below(resolve(source, git_base)) for source in operands):
            block("git rm으로 기존 추적 테스트 삭제 금지")
    elif verb == "mv":
        move = parse_move_operands(args[cursor + 1:])
        if move is not None:
            check_move(*move, git_base)
    elif verb == "update-index":
        update_args = args[cursor + 1:]
        supported_update_index_flags = {
            "--", "--add", "--again", "--assume-unchanged", "--force-remove",
            "--force-untracked-cache", "--force-write-index", "--fsmonitor-valid",
            "--ignore-missing", "--ignore-skip-worktree-entries", "--ignore-submodules",
            "--info-only", "--intent-to-add", "--no-assume-unchanged", "--no-fsmonitor-valid",
            "--no-skip-worktree", "--no-split-index", "--no-untracked-cache", "--refresh",
            "--really-refresh", "--remove", "--replace", "--skip-worktree", "--split-index",
            "--test-untracked-cache", "--unmerged", "--verbose",
        }
        supported_update_index_value_prefixes = (
            "--chmod=", "--index-version=", "--untracked-cache=",
        )
        if any(
            word.startswith("-")
            and word not in supported_update_index_flags
            and not word.startswith(supported_update_index_value_prefixes)
            and word not in {"--stdin", "--index-info", "--pathspec-from-file"}
            and not word.startswith(("--stdin=", "--index-info=", "--pathspec-from-file="))
            for word in update_args
        ):
            block("unknown/abbreviated git update-index option을 안전하게 해석할 수 없음")
        path_input_mode = any(
            word in {"--stdin", "--index-info"}
            or word.startswith(("--stdin=", "--index-info=", "--pathspec-from-file="))
            or word == "--pathspec-from-file"
            for word in update_args
        )
        if path_input_mode:
            block("git update-index stdin/path-input mutation을 안전하게 해석할 수 없음")
        if "--force-remove" in update_args and any(
            tracked_protected_paths_at_or_below(resolve(source, git_base)) for source in operands
        ):
            block("git update-index로 기존 추적 테스트 제거 금지")
    elif verb == "config":
        config_args = args[cursor + 1:]
        read_only_config = any(word in {"--get", "--get-all", "--get-regexp", "--get-urlmatch", "--list"} for word in config_args)
        config_keys = [word for word in config_args if not word.startswith("-")]
        if not read_only_config:
            block("git config mutation은 보호된 worktree 경계에서 허용되지 않음")
    elif verb == "worktree":
        worktree_args = [word for word in args[cursor + 1:] if not word.startswith("-")]
        if not worktree_args or worktree_args[0] != "list":
            block("git worktree mutation은 이 guard에서 허용되지 않음")
    else:
        known_git_reads = {"status", "diff", "log", "show", "ls-files", "rev-parse", "help", "config", "grep"}
        if verb not in known_git_reads and verb not in supported_workflow_commands:
            block("알 수 없거나 지원되지 않는 git command를 보호 경계에서 허용하지 않음")

def analyze_shell(
    shell_command: str,
    start_cwd: str,
    depth: int = 0,
    inherited_environment_assignments: dict[str, str] | None = None,
    shell_dialect: str | None = None,
) -> None:
    if depth > 2:
        block("중첩 shell 명령 깊이를 안전하게 해석할 수 없음")
    cwd = start_cwd
    inherited_assignments = dict(inherited_environment_assignments or {})
    for segment in split_segments(shell_command):
        segment = check_output_redirections(segment, cwd)
        verb, args, command_cwd, executable, environment_assignments = unwrap_command(segment, cwd)
        environment_assignment_keys = set(environment_assignments)
        active_environment_assignments = inherited_assignments | environment_assignments
        active_environment_keys = set(active_environment_assignments)
        if active_environment_keys & {"PATH", "CDPATH"}:
            block("command/path resolution environment mutation을 허용하지 않음")
        if any(
            key.startswith(("LD_", "DYLD_")) or key in {"LIBPATH", "SHLIB_PATH"}
            for key in active_environment_keys
        ):
            block("dynamic loader execution environment를 허용하지 않음")
        if active_environment_keys & {"EDITOR", "VISUAL", "PAGER", "SSH_ASKPASS"}:
            block("command execution-control environment를 허용하지 않음")
        git_context_assignments = [
            f"{key}={value}" for key, value in active_environment_assignments.items()
            if re.fullmatch(r"(?:GIT_[A-Z0-9_]*_?PATHSPECS|GIT_DIR|GIT_WORK_TREE)", key)
        ]
        if git_context_assignments:
            block("Git pathspec/repository context 환경 주입을 허용하지 않음")
        git_config_assignments = [
            f"{key}={value}" for key, value in active_environment_assignments.items()
            if re.fullmatch(r"GIT_CONFIG_[A-Z0-9_]+", key)
        ]
        if git_config_assignments:
            block("GIT_CONFIG_* 기반 git 동작 주입을 허용하지 않음")
        git_execution_environment = {
            "GIT_EDITOR", "GIT_SEQUENCE_EDITOR", "GIT_EXTERNAL_DIFF", "GIT_PAGER", "GIT_ASKPASS",
            "GIT_SSH", "GIT_SSH_COMMAND", "GIT_PROXY_COMMAND",
        }
        if active_environment_keys & git_execution_environment:
            block("Git execution-control environment를 보호 경계에서 허용하지 않음")
        autoload_environment = {
            "PERL5OPT", "PERL5LIB",
            "BASH_ENV", "ENV", "KSH_ENV", "ZDOTDIR",
        }
        if any(
            key in autoload_environment
            for key in active_environment_keys
        ):
            block("interpreter autoload environment를 보호 경계에서 허용하지 않음")
        if any(
            assignment.split("=", 1)[0].startswith("GIT_CONFIG_KEY_")
            and assignment.split("=", 1)[1].lower().startswith("alias.")
            for assignment in git_config_assignments
        ):
            block("환경 기반 git alias 주입을 안전하게 해석할 수 없음")
        if not verb:
            if environment_assignment_keys:
                block("persistent shell environment assignment을 허용하지 않음")
            continue
        if has_expansion(verb):
            block("확장된 command verb를 안전하게 해석할 수 없음")
        if "/" in executable and executable != "./gradlew":
            block("path-qualified executable은 승인된 repo wrapper 외 허용하지 않음")
        if verb in {
            "source", ".", "alias", "unalias", "function", "functions", "funcsave",
            "autoload", "enable", "zmodload", "emulate",
        }:
            block("opaque shell code-loading/mutation builtin을 허용하지 않음")
        if verb == "set" and shell_dialect == "fish":
            export_requested = any(
                argument == "--export"
                or (argument.startswith("-") and not argument.startswith("--") and "x" in argument[1:])
                for argument in args
            )
            operands = [argument for argument in args if not argument.startswith("-")]
            if export_requested and operands:
                key = operands[0]
                if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) is None:
                    block("fish export 환경변수 이름을 안전하게 해석할 수 없음")
            block("persistent shell environment mutation을 허용하지 않음")
        if verb == "setenv" and shell_dialect in {"csh", "tcsh"}:
            if not args:
                continue
            key = args[0]
            if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) is None:
                block(f"{shell_dialect} setenv 환경변수 이름을 안전하게 해석할 수 없음")
            block("persistent shell environment mutation을 허용하지 않음")
        if verb == "export":
            if args == ["-p"]:
                continue
            block("persistent shell environment mutation을 허용하지 않음")
        if verb in {"declare", "typeset", "readonly"}:
            block("persistent shell environment mutation을 허용하지 않음")
        if verb == "unset":
            block("persistent shell environment mutation을 허용하지 않음")
        if verb == "printf" and args and (
            args[0] == "-v" or re.fullmatch(r"-v[A-Za-z_][A-Za-z0-9_]*", args[0])
        ):
            block("printf -v shell variable mutation을 허용하지 않음")
        normalized_interpreter = interpreter_family(verb)
        if normalized_interpreter and has_interpreter_environment(active_environment_keys, normalized_interpreter):
            block("interpreter-prefixed environment를 보호 경계에서 허용하지 않음")
        if verb == "cd":
            if len(args) != 1 or has_expansion(args[0]):
                block("cd 경로를 안전하게 해석할 수 없음")
            candidate = os.path.normpath(os.path.join(command_cwd, args[0]))
            if os.path.isdir(candidate):
                cwd = candidate
            continue
        shell_interpreters = {"sh", "bash", "zsh", "dash", "ash", "ksh", "mksh", "csh", "tcsh", "fish", "nu"}
        is_shell_interpreter = verb in shell_interpreters or verb.endswith(("sh", "shell"))
        if is_shell_interpreter and any(
            word in {"--rcfile", "--init-file", "--init-command"}
            or word.startswith(("--rcfile=", "--init-file=", "--init-command="))
            or (verb == "fish" and word == "-C")
            for word in args
        ):
            block("shell startup-file option은 보호 경계에서 허용되지 않음")
        if is_shell_interpreter and any(re.fullmatch(r"-[A-Za-z]*c[A-Za-z]*", word) for word in args):
            index = next(index for index, word in enumerate(args) if re.fullmatch(r"-[A-Za-z]*c[A-Za-z]*", word))
            command_index = index + 1
            if command_index < len(args) and args[command_index] == "--":
                command_index += 1
            if command_index >= len(args):
                block("중첩 shell 명령을 안전하게 해석할 수 없음")
            analyze_shell(args[command_index], command_cwd, depth + 1, active_environment_assignments, verb)
            continue
        if is_shell_interpreter:
            if has_interpreter_environment(active_environment_keys):
                block("interpreter environment를 상속하는 opaque shell script 실행을 허용하지 않음")
            shell_operands = [word for word in args if not word.startswith("-")]
            if not shell_operands or has_expansion(shell_operands[0]):
                block("shell script 경로를 안전하게 해석할 수 없음")
            script_absolute = absolute_path(shell_operands[0], command_cwd)
            if not is_inside_repo(script_absolute) or not os.path.isfile(script_absolute):
                block("shell interpreter는 현재 worktree의 명시적 script 파일만 실행할 수 있음")
            script_relative = os.path.relpath(script_absolute, repo_root).replace("\\", "/")
            syntax_only = any(word == "-n" or (word.startswith("-") and "n" in word[1:]) for word in args)
            safe_shell_scripts = {
                "scripts/agent/test-codex-agent-os.sh",
                "scripts/agent/verify-changes.sh",
            }
            tracked_script = subprocess.run(
                ["git", "ls-files", "--error-unmatch", "--", script_relative],
                check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            ).returncode == 0
            if not tracked_script or (not syntax_only and script_relative not in safe_shell_scripts):
                block("opaque shell script 실행은 추적된 공식 test/verify allowlist로 제한됨")
            continue
        opaque_inline_flags = {
            "python": {"-c", "-m"}, "pypy": {"-c", "-m"},
            "perl": {"-e", "-E"}, "ruby": {"-e"},
            "node": {"-e", "--eval", "-p", "--print"},
            "php": {"-r"}, "lua": {"-e"},
        }
        if normalized_interpreter:
            if normalized_interpreter == "perl" and any(re.fullmatch(r"-(?:[mMI].*|[A-Za-z]*[mMI][A-Za-z]*)", word) for word in args):
                block("Perl module/include autoload option은 보호 경계에서 허용되지 않음")
            short_inline_option = {
                "python": "c", "pypy": "c", "perl": "eE", "ruby": "e",
                "node": "ep", "php": "r", "lua": "e",
            }
            has_combined_inline = any(
                word.startswith("-")
                and not word.startswith("--")
                and any(flag in word[1:] for flag in short_inline_option[normalized_interpreter])
                for word in args
            )
            if not args or has_combined_inline or any(word in opaque_inline_flags[normalized_interpreter] for word in args):
                block("opaque interpreter inline/module/stdin execution은 보호 경계에서 허용되지 않음")
            if any(word == "-" or word.startswith("<<") for word in args):
                block("opaque interpreter stdin/heredoc execution은 보호 경계에서 허용되지 않음")
            if normalized_interpreter in {"python", "pypy"}:
                script_candidates = []
                option_index = 0
                while option_index < len(args):
                    option = args[option_index]
                    if option == "--":
                        if option_index + 1 < len(args):
                            script_candidates.append(args[option_index + 1])
                        break
                    if not option.startswith("-"):
                        script_candidates.append(option)
                        break
                    if option in {"-W", "-X"} or option.startswith(("-W", "-X")):
                        block("Python operand-bearing runtime option은 보호 경계에서 허용되지 않음")
                    if re.fullmatch(r"-[bBdEIOPqRrSsuvVx]+", option) is None:
                        block("unknown Python option을 script 경로로 오인할 수 있음")
                    option_index += 1
            else:
                safe_interpreter_options = {
                    "perl": {"-T", "-U", "-t", "-v", "-w"},
                    "ruby": {"--disable-gems", "--verbose", "-w"},
                    "node": {"--enable-source-maps", "--no-warnings", "--trace-warnings"},
                    "php": {"-n"},
                    "lua": {"-E", "-v"},
                }
                script_candidates = []
                option_index = 0
                while option_index < len(args):
                    option = args[option_index]
                    if option == "--":
                        if option_index + 1 < len(args):
                            script_candidates.append(args[option_index + 1])
                        break
                    if not option.startswith("-"):
                        script_candidates.append(option)
                        break
                    if option not in safe_interpreter_options[normalized_interpreter]:
                        block(f"unknown/operand-bearing {normalized_interpreter} option을 script 경로로 오인할 수 있음")
                    option_index += 1
            if not script_candidates:
                block("interpreter script 경로를 안전하게 해석할 수 없음")
            script = script_candidates[0]
            if has_expansion(script):
                block("interpreter script 경로 expansion을 안전하게 해석할 수 없음")
            script_absolute = absolute_path(script, command_cwd)
            if not is_inside_repo(script_absolute) or not os.path.isfile(script_absolute):
                block("interpreter는 현재 worktree의 명시적 script 파일만 실행할 수 있음")
            script_relative = os.path.relpath(script_absolute, repo_root).replace("\\", "/")
            if tracked_protected_paths_at_or_below(script_relative):
                block("보호된 테스트/golden 파일의 interpreter 실행을 허용하지 않음")
            continue
        proven_sed_read = (
            verb == "sed"
            and len(args) == 3
            and args[0] == "-n"
            and re.fullmatch(r"\d+(?:,\d+)?p", args[1]) is not None
            and not has_expansion(args[2])
        )
        if proven_sed_read:
            continue
        if verb in {"awk", "gawk", "mawk", "nawk", "sed"}:
            block("programmable inline command는 보호 경계에서 허용되지 않음")
        if verb in {"eval", "xargs"}:
            block("동적 shell mutation 가능성을 안전하게 해석할 수 없음")
        if verb in {"rm", "unlink", "mv"}:
            operands = [word for word in args if not word.startswith("-")]
            if any(has_expansion(word) for word in operands):
                block("삭제/이동 경로 expansion을 안전하게 해석할 수 없음")
            if verb in {"rm", "unlink"}:
                if any(tracked_protected_paths_at_or_below(resolve(source, command_cwd)) for source in operands):
                    block("기존 추적 테스트 삭제 금지")
            else:
                move = parse_move_operands(args)
                if move is not None:
                    check_move(*move, command_cwd)
            continue
        find_mutations = {
            "-delete", "-exec", "-execdir", "-ok", "-okdir",
            "-fprint", "-fprint0", "-fprintf", "-fls",
        }
        find_actions = [(index, word) for index, word in enumerate(args) if word in find_mutations] if verb == "find" else []
        if find_actions:
            block("find mutation/action execution은 보호 경계에서 허용되지 않음")
        if verb == "rg":
            if "RIPGREP_CONFIG_PATH" in active_environment_keys:
                block("ripgrep config execution context를 허용하지 않음")
            safe_flags = {
                "--line-number", "--no-line-number", "--smart-case", "--only-matching", "--count",
                "--files", "--with-filename", "--no-filename", "--heading", "--no-heading",
                "--pretty", "--no-pre", "--no-search-zip",
            }
            safe_short_flags = set("nNSocHI")
            operands: list[str] = []
            cursor = 0
            options_ended = False
            while cursor < len(args):
                argument = args[cursor]
                if options_ended:
                    operands.append(argument)
                    cursor += 1
                    continue
                if argument == "--":
                    options_ended = True
                    cursor += 1
                    continue
                if argument in safe_flags:
                    cursor += 1
                    continue
                if argument in {"-g", "--glob"}:
                    if cursor + 1 >= len(args):
                        block("ripgrep glob option operand가 없음")
                    cursor += 2
                    continue
                if argument.startswith("--glob=") and argument.split("=", 1)[1]:
                    cursor += 1
                    continue
                if argument.startswith("-g") and len(argument) > 2:
                    cursor += 1
                    continue
                if argument.startswith("-") and not argument.startswith("--"):
                    if len(argument) > 1 and all(flag in safe_short_flags for flag in argument[1:]):
                        cursor += 1
                        continue
                    block("ripgrep short option이 승인된 read grammar에 없음")
                if argument.startswith("--"):
                    block("ripgrep long option이 승인된 read grammar에 없음")
                operands.append(argument)
                cursor += 1
            if "--files" not in args and not operands:
                block("ripgrep search pattern/path operand가 없음")
            continue
        if verb == "git":
            git_environment = {
                key: value for key, value in active_environment_assignments.items()
                if key.startswith("GIT_")
            }
            if git_environment and git_environment != {"GIT_OPTIONAL_LOCKS": "0"}:
                block("Git environment이 승인된 read/workflow grammar에 없음")
            if any(has_expansion(word) for word in args):
                block("git mutation 경로 expansion을 안전하게 해석할 수 없음")
            analyze_git(args, command_cwd)
            continue
        if verb == "gradlew":
            if executable != "./gradlew" or os.path.normpath(command_cwd) != repo_root:
                block("Gradle은 현재 worktree root의 literal wrapper만 실행할 수 있음")
            for key, value in active_environment_assignments.items():
                canonical_java_home = key == "JAVA_HOME" and value == "/__codex_java_home_21__"
                if not canonical_java_home:
                    block("Gradle/JVM execution-control environment를 허용하지 않음")
            if any(has_expansion(argument) for argument in args):
                block("Gradle task/option expansion을 허용하지 않음")
            safe_gradle_flags = {
                "--version", "--no-daemon", "--rerun-tasks", "--stacktrace",
                "--info", "--quiet", "--offline", "--continue", "--dry-run",
            }
            safe_gradle_tasks = {"build", "check", "test"}
            cursor = 0
            while cursor < len(args):
                argument = args[cursor]
                if argument in safe_gradle_flags:
                    cursor += 1
                    continue
                if argument == "--tests":
                    if cursor + 1 >= len(args) or re.fullmatch(r"[A-Za-z0-9_.-]+", args[cursor + 1]) is None:
                        block("Gradle test filter를 안전하게 해석할 수 없음")
                    cursor += 2
                    continue
                if argument in safe_gradle_tasks or re.fullmatch(r":(?:[A-Za-z0-9_.-]+:)*(?:test|check|build)", argument):
                    cursor += 1
                    continue
                block("Gradle task/option이 승인된 검증 grammar에 없음")
            if not args:
                block("Gradle task 없는 실행을 허용하지 않음")
            continue
        safe_read_only = {"grep", "egrep", "fgrep", "echo", "printf", "cat", "head", "tail", "wc", "ls", "stat", "pwd", "find", "git"}
        if verb in safe_read_only:
            continue
        if verb not in safe_read_only and text_references_protected(args, command_cwd):
            block("알 수 없는 command가 보호 경로를 참조함")
        if verb not in safe_read_only and any(has_expansion(word) for word in args):
            block("실행 명령의 unresolved expansion을 안전하게 해석할 수 없음")
        nested_destructive = any(command_name(word) in {"rm", "unlink", "mv"} for word in args)
        tracked_test_operand = nested_destructive and any(
            not word.startswith("-")
            and not has_expansion(word)
            and bool(tracked_protected_paths_at_or_below(resolve(word, command_cwd)))
            for word in args
        )
        if verb not in safe_read_only and nested_destructive and tracked_test_operand:
            block("알 수 없는 execution wrapper의 테스트 mutation을 안전하게 해석할 수 없음")
        block("closed shell grammar에 없는 command segment를 허용하지 않음")

analyze_shell(guard_command, repo_root)

protected = r"(?:\.?/?legacy(?:/|$)|[^\s\x22\x27]*/legacy/[^\s\x22\x27]*|[^\s\x22\x27]*/src/(?:test|main)/resources/golden/[^\s\x22\x27]*)"
redirection = re.compile(r"(?:>|>>)\s*[\x22\x27]?" + protected, re.I)
tee_write = re.compile(r"\btee\b[^;&|]*" + protected, re.I)
mutator = re.compile(
    r"(?:^|[;&|]\s*)(?:sudo\s+)?(?:cp|touch|mkdir|install|truncate|chmod|chown|ln|apply_patch|"
    r"git\s+(?:checkout|restore|clean)|sed\b[^;&|]*\s-i\b|perl\b[^;&|]*\s-pi\b)"
    r"[^;&|]*" + protected,
    re.I,
)
if redirection.search(command) or tee_write.search(command) or mutator.search(command):
    block("shell command가 legacy 또는 golden 경로를 수정하려 함")
'
