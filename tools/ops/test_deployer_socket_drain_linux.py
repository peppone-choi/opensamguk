#!/usr/bin/env python3
"""Opt-in, owned Linux namespace proof; never publishes ports or uses host PID.

RUN_DEPLOYER_SOCKET_DRAIN_LINUX_TESTS=1 DOCKER_CONTEXT=desktop-linux python3
tools/ops/test_deployer_socket_drain_linux.py [--test SCENARIO]

Full commands, outputs, fixture events and cleanup inventories are recorded in
--evidence (default: .superpowers/sdd/pep-recovery-operator/task-1-linux.json).
Only the final synthetic PASS marker is printed on success.
"""
import argparse
from dataclasses import asdict
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

import deployer_socket_drain as drain

LABEL = 'org.opensamguk.socket-drain.fixture'
SCENARIOS = ('existing_connection_survives_new_syn_fence', 'disconnected_handler_fd_survives')


def checked(args):
    result = subprocess.run(args, text=True, capture_output=True)
    with Path('/tmp/fixture-command-log.jsonl').open('a') as output:
        output.write(json.dumps({'argv': args, 'exit': result.returncode,
                                 'stdout': result.stdout, 'stderr': result.stderr}) + '\n')
    result.check_returncode()
    return result.stdout


def inside(action, token):
    """Run only after Docker isolation checks; /proc here belongs to the fixture."""
    if action == 'inspect':
        report = drain.inspect_port_drain(Path('/proc'), 1)
        print(json.dumps({**asdict(report), 'drained': report.drained}))
    elif action == 'install':
        try:
            fence = drain.install_syn_fence(checked, 1, 9000, token)
        except drain.FenceError as error:
            if error.fence is not None:
                Path('/tmp/owned-fence.json').write_text(json.dumps(asdict(error.fence)))
            raise
        Path('/tmp/owned-fence.json').write_text(json.dumps(asdict(fence)))
        print(json.dumps(asdict(fence)))
    elif action == 'remove':
        path = Path('/tmp/owned-fence.json')
        if path.exists():
            value = json.loads(path.read_text())
            value['rules'] = tuple((binary, tuple(rule)) for binary, rule in value['rules'])
            fence = drain.OwnedFence(**value)
            assert fence.token == token
            drain.remove_owned_fence(checked, fence)
        counts = {}
        for binary in ('iptables', 'ip6tables'):
            listing = checked([binary, '-w', '5', '-S', 'INPUT'])
            counts[binary] = listing.count('opensamguk-drain:' + token)
        assert counts == {'iptables': 0, 'ip6tables': 0}, counts
        print(json.dumps(counts))
    else:
        raise ValueError(action)


class Fixture:
    def __init__(self, evidence):
        self.token = uuid.uuid4().hex
        self.prefix = 'socket-drain-' + self.token
        self.tag = self.prefix + ':fixture'
        self.evidence = evidence
        self.trace = {'token': self.token, 'commands': [], 'observations': [], 'cleanup': {}}
        self.containers = []
        self.network = None
        self.image = None
        self.server = None

    def docker(self, *args, check=True, timeout=120):
        command = ['docker', '--context', 'desktop-linux', *args]
        result = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
        self.trace['commands'].append({'argv': command, 'exit': result.returncode,
                                       'stdout': result.stdout, 'stderr': result.stderr})
        if check and result.returncode:
            raise RuntimeError(f'Docker command failed: {args[:3]}: {result.stderr}')
        return result.stdout.strip()

    def obj(self, kind, name):
        return json.loads(self.docker(kind, 'inspect', name))[0]

    def event(self, container, name):
        raw = self.docker('exec', container, '/fixture', 'event', name)
        return json.loads(raw) if raw else None

    def wait(self, description, predicate):
        deadline = time.monotonic() + 30
        last = None
        while time.monotonic() < deadline:
            last = predicate()
            if last:
                self.trace['observations'].append({'event': description, 'value': last})
                return last
            # Bounded polling only: events and /proc are the assertion authority.
            time.sleep(0.05)
        raise AssertionError(f'timed out awaiting {description}; last={last!r}')

    def control(self, container, name):
        self.docker('exec', container, '/fixture', 'signal', name)

    def inspect(self):
        return json.loads(self.docker('exec', self.server, 'python3', '/test.py', '--inside', 'inspect'))

    def setup(self):
        assert os.environ.get('DOCKER_CONTEXT') == 'desktop-linux', 'DOCKER_CONTEXT must be desktop-linux'
        assert self.docker('version', '--format', '{{.Server.Os}}/{{.Server.Arch}}') == 'linux/amd64'
        for kind, command in [('containers', ('ps', '-a')), ('networks', ('network', 'ls')),
                              ('images', ('image', 'ls'))]:
            inventory = self.docker(*command, '--filter', 'label=' + LABEL, '--format', '{{.ID}}')
            self.trace['cleanup']['before_' + kind] = inventory
            assert not inventory, f'pre-existing socket-drain fixture {kind}; do not remove without ownership'
        directory = Path(__file__).resolve().parent
        context = directory / 'fixtures/deployer_socket_drain'
        assert (context / 'Dockerfile').is_file(), 'owned fixture Dockerfile is missing'
        assert (context / 'server.go').is_file(), 'Go 1.23 fixture implementation is missing'
        self.docker('build', '--platform', 'linux/amd64', '--label', LABEL + '=' + self.token,
                    '-t', self.tag, str(context), timeout=900)
        self.image = self.obj('image', self.tag)['Id']
        network_name = self.prefix + '-net'
        identity = self.docker('network', 'create', '--internal', '--ipv6', '--label',
                               LABEL + '=' + self.token, network_name)
        self.network = (network_name, identity)
        assert self.obj('network', network_name)['Internal']
        for role in ('server', 'peer-one', 'peer-two'):
            name = self.prefix + '-' + role
            args = ['container', 'create', '--name', name, '--network', network_name,
                    '--label', LABEL + '=' + self.token, '--cap-drop', 'ALL']
            if role == 'server':
                args += ['--cap-add', 'NET_ADMIN', '--cap-add', 'SYS_ADMIN']
            else:
                args += ['--user', '65534:65534']
            args += [self.tag, 'server' if role == 'server' else 'idle']
            identity = self.docker(*args)
            self.containers.append((name, identity))
            obj = self.obj('container', name)
            host = obj['HostConfig']
            assert not host['Privileged'] and not host['PortBindings'] and host['PidMode'] != 'host'
            assert host['CapDrop'] == ['ALL']
            capabilities = {value.removeprefix('CAP_') for value in (host['CapAdd'] or [])}
            assert capabilities == ({'NET_ADMIN', 'SYS_ADMIN'} if role == 'server' else set())
            self.docker('container', 'start', name)
        self.server, self.peer_one, self.peer_two = [name for name, _ in self.containers]
        self.docker('cp', str(directory / 'deployer_socket_drain.py'), self.server + ':/deployer_socket_drain.py')
        self.docker('cp', str(Path(__file__).resolve()), self.server + ':/test.py')
        ready = self.wait('Go 1.23 server listening', lambda: self.event(self.server, 'ready'))
        assert ready['go_version'].startswith('go1.23.'), ready
        network = self.obj('container', self.server)['NetworkSettings']['Networks'][network_name]
        self.address = network['IPAddress'] + ':9000'
        self.address6 = '[' + network['GlobalIPv6Address'] + ']:9000'
        assert network['GlobalIPv6Address']
        self.wait('initial listener-only drain', lambda: (r if (r := self.inspect())['drained'] else None))

    def start_client(self, peer, mode, identity):
        self.docker('exec', '-d', peer, '/fixture', mode, self.address, identity)
        return self.wait(identity + ' handler entered', lambda: self.event(self.server, 'entered-' + identity))

    def run(self, scenario):
        # Both requests enter before admission is fenced; no new accepted sockets
        # can be created by either peer after this point.
        held = self.start_client(self.peer_one, 'hold', 'held')
        reset_mode = 'hold' if scenario == SCENARIOS[0] else 'reset'
        reset = self.start_client(self.peer_two, reset_mode, 'reset')
        report = self.inspect()
        assert not report['drained']
        assert any(e['inode'] == held['inode'] and e['state'] == '01' for e in report['entries'])
        self.docker('exec', self.server, 'python3', '/test.py', '--inside', 'install', '--token', self.token)
        for address in (self.address, self.address6):
            rejected = json.loads(self.docker('exec', self.peer_two, '/fixture', 'reject', address))
            assert rejected['rejected'] and rejected['connection_refused'], rejected
            self.trace['observations'].append({'event': 'new non-loopback SYN rejected', 'value': rejected})
        health = json.loads(self.docker('exec', self.server, '/fixture', 'health', '127.0.0.1:9000'))
        assert health['status'] == 200
        self.trace['observations'].append({'event': 'loopback health through fence', 'value': health})
        self.control(self.server, 'release-held')
        response = self.wait('existing held connection completes through fence',
                             lambda: self.event(self.peer_one, 'response-held'))
        assert response['status'] == 200 and response['body'] == 'released\n', response
        self.wait('held connection closed', lambda: self.event(self.server, 'closed-held'))
        if scenario in (None, SCENARIOS[1]):
            self.control(self.peer_two, 'rst-reset')
            self.wait('peer sent RST', lambda: self.event(self.peer_two, 'rst-reset'))
            cancelled = self.wait('request context cancelled with handler still blocked',
                                  lambda: self.event(self.server, 'cancelled-reset'))
            assert cancelled['blocked'] and cancelled['inode'] == reset['inode']
            def retained_descriptor():
                report = self.inspect()
                missing = not any(e['inode'] == reset['inode'] for e in report['entries'])
                retained = any(s['inode'] == reset['inode'] and s['classification'] == 'unmapped'
                               for s in report['sockets'])
                return report if missing and retained else None
            report = self.wait('RST removes TCP row but handler retains unmapped FD', retained_descriptor)
            assert not report['drained'], report
            assert all(e['state'] in ('0A', '06') for e in report['entries'] if e['local_port'] == 9000)
            assert [s for s in report['sockets'] if s['classification'] != 'listener'] == [
                {'fd': reset['fd'], 'inode': reset['inode'], 'classification': 'unmapped'}]
            assert self.docker('exec', self.server, 'readlink', f"/proc/1/fd/{reset['fd']}") == f"socket:[{reset['inode']}]"
            for table in ('tcp', 'tcp6'):
                raw = self.docker('exec', self.server, 'cat', '/proc/1/net/' + table)
                assert all(line.split()[9] != str(reset['inode']) for line in raw.splitlines()[1:])
            assert self.event(self.server, 'returned-reset') is None
            assert self.event(self.server, 'closed-reset') is None
            self.trace['observations'].append({'event': 'retained descriptor drain rejected', 'value': report})
        self.control(self.server, 'release-reset')
        self.wait('reset handler returned after explicit release', lambda: self.event(self.server, 'returned-reset'))
        self.wait('reset connection closed', lambda: self.event(self.server, 'closed-reset'))
        self.wait('final listener-only drain after closed events', lambda: (r if (r := self.inspect())['drained'] else None))

    def cleanup(self):
        errors = []
        if self.server:
            try:
                self.trace['cleanup']['exact_comment_rules_before_namespace_removal'] = json.loads(
                    self.docker('exec', self.server, 'python3', '/test.py', '--inside', 'remove', '--token', self.token))
                commands = self.docker('exec', self.server, 'cat', '/tmp/fixture-command-log.jsonl')
                self.trace['namespace_commands'] = [json.loads(line) for line in commands.splitlines()]
            except Exception as error:
                errors.append(str(error))
        for name, identity in reversed(self.containers):
            try:
                obj = self.obj('container', name)
                assert obj['Id'] == identity and obj['Config']['Labels'][LABEL] == self.token
                self.docker('container', 'rm', '--force', identity)
            except Exception as error:
                errors.append(str(error))
        if self.network:
            try:
                name, identity = self.network
                obj = self.obj('network', name)
                assert obj['Id'] == identity and obj['Labels'][LABEL] == self.token
                self.docker('network', 'rm', identity)
            except Exception as error:
                errors.append(str(error))
        if self.image:
            try:
                obj = self.obj('image', self.tag)
                assert obj['Id'] == self.image and obj['Config']['Labels'][LABEL] == self.token
                self.docker('image', 'rm', self.tag)
            except Exception as error:
                errors.append(str(error))
        for kind, command in [('containers', ('ps', '-a')), ('networks', ('network', 'ls')),
                              ('images', ('image', 'ls'))]:
            try:
                inventory = self.docker(*command, '--filter', 'label=' + LABEL, '--format', '{{.ID}}')
                self.trace['cleanup']['after_' + kind] = inventory
                assert not inventory, inventory
            except Exception as error:
                errors.append(str(error))
        self.trace['cleanup']['errors'] = errors
        self.evidence.parent.mkdir(parents=True, exist_ok=True)
        self.evidence.write_text(json.dumps(self.trace, indent=2) + '\n')
        if errors:
            raise AssertionError('exact fixture cleanup failed: ' + '; '.join(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--test', choices=SCENARIOS)
    parser.add_argument('--inside', choices=('inspect', 'install', 'remove'), help=argparse.SUPPRESS)
    parser.add_argument('--token', help=argparse.SUPPRESS)
    parser.add_argument('--evidence', type=Path, default=Path('.superpowers/sdd/pep-recovery-operator/task-1-linux.json'))
    args = parser.parse_args()
    if args.inside:
        assert sys.platform == 'linux' and Path('/fixture').is_file()
        inside(args.inside, args.token)
        return
    if os.environ.get('RUN_DEPLOYER_SOCKET_DRAIN_LINUX_TESTS') != '1':
        raise SystemExit('NOT RUN: explicitly set RUN_DEPLOYER_SOCKET_DRAIN_LINUX_TESTS=1.')
    fixture = Fixture(args.evidence)
    try:
        fixture.setup()
        fixture.run(args.test)
    finally:
        fixture.cleanup()
    print('Synthetic socket-drain proof: PASS')


if __name__ == '__main__':
    main()
