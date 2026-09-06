"""Fail-closed Linux HTTP/1.1 drain observations and narrowly owned SYN fences.

The caller must hold its operation lock and keep admission fenced through the
subsequent synchronous barrier. An observation is not an atomic idle lease.
Both TCP tables must be readable, including an empty tcp6 on disabled IPv6.
Runner is a checked callable: argv -> stdout text, raising on command failure.
No shell, process cancellation, chain flushing or policy changes are used.
"""
from dataclasses import dataclass
from pathlib import Path
import os
import re
import shlex
from typing import Callable


@dataclass(frozen=True)
class TcpEntry:
    family: int
    local_address: str
    local_port: int
    remote_address: str
    remote_port: int
    state: str
    tx_queue: int
    rx_queue: int
    inode: int


@dataclass(frozen=True)
class ProcessSocket:
    fd: int
    inode: int
    classification: str


@dataclass(frozen=True)
class PortDrainReport:
    pid: int
    port: int
    entries: tuple[TcpEntry, ...]
    sockets: tuple[ProcessSocket, ...]
    listener_inodes: tuple[int, ...]
    blockers: tuple[str, ...]

    @property
    def drained(self) -> bool:
        return not self.blockers


@dataclass(frozen=True)
class OwnedFence:
    pid: int
    port: int
    token: str
    start_time: int
    netns: str
    rules: tuple[tuple[str, tuple[str, ...]], ...]


class FenceError(RuntimeError):
    """A fence failure retains its ownership record; never blindly undo it."""
    def __init__(self, message: str, fence: OwnedFence | None = None):
        super().__init__(message)
        self.fence = fence


Runner = Callable[[list[str]], str]


def _validate(pid: int, port: int) -> None:
    if type(pid) is not int or pid <= 0 or type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('positive PID and valid TCP port required')


def _start_time(stat: str) -> int:
    # comm may contain spaces and parentheses; fields after the final ')' start at 3.
    result = int(stat[stat.rindex(')') + 2:].split()[19])
    if result <= 0:
        raise ValueError('invalid process start time')
    return result


def _parse_table(path: Path, family: int) -> tuple[TcpEntry, ...]:
    lines = path.read_text().splitlines()
    if not lines or not all(word in lines[0] for word in ('local_address', 'st', 'inode')):
        raise ValueError('invalid TCP table header')
    entries = []
    width = 8 if family == 4 else 32
    for line in lines[1:]:
        cols = line.split()
        if len(cols) < 10:
            raise ValueError('incomplete TCP row')
        local, lp = cols[1].split(':')
        remote, rp = cols[2].split(':')
        tx, rx = cols[4].split(':')
        if not all(re.fullmatch('[0-9A-Fa-f]{' + str(n) + '}', value)
                   for value, n in ((local, width), (remote, width), (lp, 4), (rp, 4),
                                    (cols[3], 2), (tx, 8), (rx, 8))):
            raise ValueError('malformed TCP fields')
        inode = int(cols[9])
        if inode < 0:
            raise ValueError('negative socket inode')
        entries.append(TcpEntry(family, local.upper(), int(lp, 16), remote.upper(), int(rp, 16),
                                cols[3].upper(), int(tx, 16), int(rx, 16), inode))
    return tuple(entries)


def _fds(path: Path) -> tuple[tuple[int, str], ...]:
    return tuple(sorted((int(fd.name), os.readlink(fd)) for fd in path.iterdir()))


def inspect_port_drain(proc_root: Path, pid: int, port: int = 9000) -> PortDrainReport:
    """Read whole namespace tables before classifying every process socket FD.

    Only one owned listener per address family is expected. All other socket
    FDs, even an otherwise terminal TCP socket, block. Races/read failures block.
    """
    _validate(pid, port)
    base = Path(proc_root) / str(pid)
    entries, sockets, listeners, blockers = (), [], (), []
    try:
        identity = (_start_time((base / 'stat').read_text()), os.readlink(base / 'ns/net'))
        entries = _parse_table(base / 'net/tcp', 4) + _parse_table(base / 'net/tcp6', 6)
        local = tuple(e for e in entries if e.local_port == port)
        listening = tuple(e for e in local if e.state == '0A')
        listeners = tuple(sorted(e.inode for e in listening))
        if not listening or any(e.inode == 0 for e in listening):
            blockers.append('missing or invalid listener')
        if any(sum(e.family == family for e in listening) > 1 for family in (4, 6)):
            blockers.append('multiple listeners in one address family')
        for entry in local:
            if entry.state not in ('0A', '06'):
                blockers.append(f'local port {port} has TCP state {entry.state}')
            if entry.state == '0A' and entry.rx_queue:
                blockers.append('listener receive backlog is not zero')
        # Table-first order is intentional: unaccepted connections may have no FD.
        descriptors = _fds(base / 'fd')
        for fd, target in descriptors:
            if not target.startswith('socket:'):
                continue
            match = re.fullmatch(r'socket:\[(\d+)\]', target)
            if match is None:
                raise ValueError('malformed socket descriptor')
            inode = int(match[1])
            mapped = tuple(e for e in entries if e.inode == inode and inode != 0)
            classification = 'unmapped' if not mapped else 'non-listener'
            if len(mapped) == 1 and mapped[0] in listening:
                classification = 'listener'
            sockets.append(ProcessSocket(fd, inode, classification))
            if classification != 'listener':
                blockers.append(f'socket FD {fd} inode {inode} is {classification}')
        if set(listeners) != {s.inode for s in sockets if s.classification == 'listener'}:
            blockers.append('namespace listener is not owned by target process')
        if descriptors != _fds(base / 'fd'):
            blockers.append('process descriptors changed during inspection')
        # Compare semantic fields, not retransmission timers omitted from TcpEntry.
        if entries != _parse_table(base / 'net/tcp', 4) + _parse_table(base / 'net/tcp6', 6):
            blockers.append('TCP tables changed during inspection')
        if identity != (_start_time((base / 'stat').read_text()), os.readlink(base / 'ns/net')):
            blockers.append('process identity changed during inspection')
    except (OSError, ValueError, IndexError) as error:
        blockers.append(f'incomplete proc observation: {type(error).__name__}')
    return PortDrainReport(pid, port, entries, tuple(sockets), listeners, tuple(blockers))


def _identity(runner: Runner, pid: int) -> tuple[int, str]:
    start = _start_time(runner(['cat', f'/proc/{pid}/stat']))
    namespace = runner(['readlink', f'/proc/{pid}/ns/net']).strip()
    if not re.fullmatch(r'net:\[\d+\]', namespace):
        raise ValueError('invalid namespace identity')
    return start, namespace


def _ns(runner: Runner, pid: int, args: list[str]) -> str:
    return runner(['nsenter', '--target', str(pid), '--net', '--', *args])


def _rules(runner: Runner, pid: int, binary: str) -> tuple[tuple[str, ...], ...]:
    output = _ns(runner, pid, [binary, '-w', '5', '-S', 'INPUT'])
    rules = []
    policies = 0
    for line in output.splitlines():
        parts = shlex.split(line)
        if parts[:2] == ['-A', 'INPUT']:
            rules.append(tuple(parts[2:]))
        elif len(parts) == 3 and parts[:2] == ['-P', 'INPUT'] and parts[2] in ('ACCEPT', 'DROP'):
            policies += 1
        else:
            raise ValueError('unrecognized firewall listing')
    if policies != 1:
        raise ValueError('incomplete firewall listing')
    return tuple(rules)


def _commented(rules: tuple[tuple[str, ...], ...], comment: str) -> tuple[tuple[str, ...], ...]:
    return tuple(rule for rule in rules if any(rule[i:i + 2] == ('--comment', comment)
                                             for i in range(len(rule) - 1)))


def _check_identity(runner: Runner, fence: OwnedFence) -> None:
    if _identity(runner, fence.pid) != (fence.start_time, fence.netns):
        raise FenceError('target process or namespace identity changed', fence)


def install_syn_fence(runner: Runner, pid: int, port: int, token: str) -> OwnedFence:
    """Insert exact IPv4/IPv6 NEW non-loopback SYN rejects; leave fence on failure.

    FenceError.fence carries attempted rules for explicit identity-checked cleanup
    after partial installation. A collision is checked across families first.
    """
    _validate(pid, port)
    if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', token):
        raise ValueError('a unique safe fence token (1..64 characters) is required')
    fence = None
    try:
        start, netns = _identity(runner, pid)
        ipv6 = _ns(runner, pid, ['cat', '/proc/sys/net/ipv6/conf/all/disable_ipv6']).strip()
        if ipv6 not in ('0', '1'):
            raise ValueError('IPv6 enabled state is unknown')
        interfaces6 = _ns(runner, pid, ['cat', '/proc/net/if_inet6']).strip()
        comment = 'opensamguk-drain:' + token
        spec = ('!', '-i', 'lo', '-p', 'tcp', '-m', 'tcp', '--dport', str(port),
                '--tcp-flags', 'FIN,SYN,RST,ACK', 'SYN', '-m', 'conntrack', '--ctstate', 'NEW',
                '-m', 'comment', '--comment', comment, '-j', 'REJECT', '--reject-with', 'tcp-reset')
        binaries = ('iptables', 'ip6tables') if ipv6 == '0' or interfaces6 else ('iptables',)
        planned = tuple((binary, spec) for binary in binaries)
        for binary in binaries:
            if _commented(_rules(runner, pid, binary), comment):
                raise FenceError('fence comment collision')
        fence = OwnedFence(pid, port, token, start, netns, planned)
        for binary, rule in planned:
            _check_identity(runner, fence)
            _ns(runner, pid, [binary, '-w', '5', '-I', 'INPUT', *rule])
            if _commented(_rules(runner, pid, binary), comment) != (rule,):
                raise FenceError('inserted firewall rule did not verify', fence)
        _check_identity(runner, fence)
        return fence
    except Exception as error:
        if isinstance(error, FenceError):
            raise
        raise FenceError(f'fence installation failed: {type(error).__name__}', fence) from error


def remove_owned_fence(runner: Runner, fence: OwnedFence) -> None:
    """Preflight all exact identities, delete by full specification, verify absent.

    Absence is allowed for retry/partial installation; modified/duplicate owned
    comments block before any deletion. The caller serializes firewall writers.
    """
    try:
        _check_identity(runner, fence)
        comment = 'opensamguk-drain:' + fence.token
        for binary, rule in fence.rules:
            found = _commented(_rules(runner, fence.pid, binary), comment)
            if found not in ((), (rule,)):
                raise FenceError('owned firewall rule identity is ambiguous', fence)
        for binary, rule in fence.rules:
            _check_identity(runner, fence)
            found = _commented(_rules(runner, fence.pid, binary), comment)
            if found == (rule,):
                _ns(runner, fence.pid, [binary, '-w', '5', '-D', 'INPUT', *rule])
            elif found:
                raise FenceError('owned rule changed before deletion', fence)
            if _commented(_rules(runner, fence.pid, binary), comment):
                raise FenceError('owned firewall rule remains after deletion', fence)
    except Exception as error:
        if isinstance(error, FenceError):
            raise
        raise FenceError(f'fence cleanup failed: {type(error).__name__}', fence) from error
