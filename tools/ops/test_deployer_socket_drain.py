#!/usr/bin/env python3
"""Fast, synthetic /proc and exact-command firewall regression tests."""
from pathlib import Path
import shlex
import tempfile
import unittest

try:
    import deployer_socket_drain as drain
except ModuleNotFoundError:
    drain = None


HEADER = '  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n'


def row(inode=123, state='0A', port=9000, rx=0, family=4):
    address = '00000000' if family == 4 else '0' * 32
    return f'0: {address}:{port:04X} {address}:0000 {state} 00000000:{rx:08X} 00:00000000 00000000 0 0 {inode} 1 0\n'


class ProcTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(drain, 'socket drain implementation is missing')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.pid = self.root / '42'
        (self.pid / 'net').mkdir(parents=True)
        (self.pid / 'fd').mkdir()
        (self.pid / 'ns').mkdir()
        (self.pid / 'ns/net').symlink_to('net:[777]')
        (self.pid / 'stat').write_text('42 (fixture with spaces) S ' + '0 ' * 18 + '12345 0\n')
        self.tables(row())
        self.fd(3, 123)

    def tables(self, tcp='', tcp6=''):
        (self.pid / 'net/tcp').write_text(HEADER + tcp)
        (self.pid / 'net/tcp6').write_text(HEADER + tcp6)

    def fd(self, fd, inode):
        (self.pid / f'fd/{fd}').symlink_to(f'socket:[{inode}]')

    def report(self):
        return drain.inspect_port_drain(self.root, 42)

    def test_listener_only_and_time_wait_are_drained(self):
        self.tables(row() + row(0, '06'))
        result = self.report()
        self.assertTrue(result.drained, result.blockers)
        self.assertEqual(result.listener_inodes, (123,))
        self.assertEqual(result.sockets[0].classification, 'listener')

    def test_ipv6_listener_is_mapped(self):
        self.tables('', row(family=6))
        self.assertTrue(self.report().drained)
        self.assertEqual(self.report().entries[0].family, 6)

    def test_dual_family_listeners_are_permitted(self):
        self.tables(row(), row(124, family=6))
        self.fd(4, 124)
        self.assertTrue(self.report().drained)

    def test_backlog_blocks_even_without_accepted_fd(self):
        self.tables(row(rx=1))
        self.assertFalse(self.report().drained)

    def test_all_nonterminal_states_block_namespace_wide(self):
        for state in ('01', '02', '03', '04', '05', '07', '08', '09', '0B', '0C', 'FF'):
            with self.subTest(state=state):
                self.tables(row() + row(0, state))
                self.assertFalse(self.report().drained)

    def test_ipv6_unaccepted_syn_and_time_wait_fd_cannot_escape_gate(self):
        self.tables(row(), row(0, '03', family=6))
        self.assertFalse(self.report().drained)
        self.tables(row(), row(777, '06', family=6))
        self.fd(7, 777)
        self.assertFalse(self.report().drained)

    def test_address_port_and_queue_fields_are_parsed(self):
        self.tables(row() + '1: 0100007F:C350 0200007F:01BB 01 00000002:00000003 '
                    '00:00000000 00000000 0 0 777 1 0\n')
        result = self.report()
        self.assertTrue(result.drained)
        entry = result.entries[1]
        self.assertEqual((entry.local_address, entry.local_port, entry.remote_address, entry.remote_port,
                          entry.state, entry.tx_queue, entry.rx_queue, entry.inode),
                         ('0100007F', 50000, '0200007F', 443, '01', 2, 3, 777))

    def test_unmapped_retained_socket_blocks(self):
        self.fd(7, 777)
        result = self.report()
        self.assertFalse(result.drained)
        self.assertEqual(result.sockets[-1].classification, 'unmapped')

    def test_outbound_and_foreign_listener_sockets_block(self):
        for state in ('01', '0A'):
            with self.subTest(state=state):
                self.tables(row() + row(777, state, port=50000))
                self.fd(7, 777)
                self.assertFalse(self.report().drained)
                (self.pid / 'fd/7').unlink()

    def test_unowned_and_duplicate_same_family_listeners_block(self):
        self.tables(row() + row(124))
        self.assertFalse(self.report().drained)
        self.fd(4, 124)
        self.assertFalse(self.report().drained)

    def test_missing_listener_and_unreadable_or_malformed_tables_block(self):
        self.tables()
        self.assertFalse(self.report().drained)
        self.tables('bad row\n')
        self.assertFalse(self.report().drained)
        (self.pid / 'net/tcp6').unlink()
        self.assertFalse(self.report().drained)

    def test_malformed_socket_link_and_duplicate_inode_mapping_block(self):
        (self.pid / 'fd/7').symlink_to('socket:[bad]')
        self.assertFalse(self.report().drained)
        (self.pid / 'fd/7').unlink()
        self.tables(row() + row(123, '01', 40000))
        self.assertFalse(self.report().drained)


class Firewall:
    """Command boundary substitute; production namespace commands run in Linux suite."""
    def __init__(self, ipv6=True):
        self.ipv6 = ipv6
        self.rules = {'iptables': [], 'ip6tables': []}
        self.calls = []
        self.identity = '42 (fixture) S ' + '0 ' * 18 + '12345 0\n'
        self.netns = 'net:[777]\n'
        self.drop_insert = False
        self.ipv6_interfaces = ''
        self.fail_ipv6_insert = False
        self.empty_listing = False

    def __call__(self, args):
        self.calls.append(tuple(args))
        if args == ['cat', '/proc/42/stat']:
            return self.identity
        if args == ['readlink', '/proc/42/ns/net']:
            return self.netns
        assert args[:5] == ['nsenter', '--target', '42', '--net', '--'], args
        cmd = args[5:]
        if cmd == ['cat', '/proc/sys/net/ipv6/conf/all/disable_ipv6']:
            return '0\n' if self.ipv6 else '1\n'
        if cmd == ['cat', '/proc/net/if_inet6']:
            return self.ipv6_interfaces
        binary, wait, timeout, action, chain, *rule = cmd
        assert (binary in self.rules and (wait, timeout, chain) == ('-w', '5', 'INPUT')), cmd
        rules = self.rules[binary]
        if action == '-S':
            if self.empty_listing:
                return ''
            return '-P INPUT ACCEPT\n' + ''.join(shlex.join(['-A', 'INPUT', *r]) + '\n' for r in rules)
        if action == '-I':
            if binary == 'ip6tables' and self.fail_ipv6_insert:
                raise RuntimeError('synthetic IPv6 insertion failure')
            if not self.drop_insert:
                rules.insert(0, tuple(rule))
            return ''
        if action == '-D':
            rules.remove(tuple(rule))
            return ''
        raise AssertionError(cmd)


class FenceTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(drain, 'socket drain implementation is missing')
        self.runner = Firewall()

    def test_dual_stack_fence_and_exact_owned_cleanup(self):
        fence = drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        for binary in ('iptables', 'ip6tables'):
            rule = self.runner.rules[binary][0]
            self.assertEqual(rule, ('!', '-i', 'lo', '-p', 'tcp', '-m', 'tcp', '--dport', '9000',
                '--tcp-flags', 'FIN,SYN,RST,ACK', 'SYN', '-m', 'conntrack', '--ctstate', 'NEW',
                '-m', 'comment', '--comment', 'opensamguk-drain:fixture-123', '-j', 'REJECT',
                '--reject-with', 'tcp-reset'))
        self.runner.rules['iptables'].append(('-p', 'tcp', '-j', 'ACCEPT'))
        drain.remove_owned_fence(self.runner, fence)
        self.assertEqual(self.runner.rules['iptables'], [('-p', 'tcp', '-j', 'ACCEPT')])
        self.assertEqual(self.runner.rules['ip6tables'], [])

    def test_collision_does_not_mutate_either_family(self):
        self.runner.rules['ip6tables'] = [('-m', 'comment', '--comment', 'opensamguk-drain:fixture-123', '-j', 'ACCEPT')]
        with self.assertRaises(drain.FenceError):
            drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        self.assertEqual(self.runner.rules['iptables'], [])

    def test_ipv6_disabled_uses_ipv4_only(self):
        self.runner.ipv6 = False
        fence = drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        self.assertEqual(len(fence.rules), 1)
        self.assertFalse(any('ip6tables' in c for c in self.runner.calls))
        drain.remove_owned_fence(self.runner, fence)

    def test_ipv6_interface_enabled_despite_global_disable_still_gets_fence(self):
        self.runner.ipv6 = False
        self.runner.ipv6_interfaces = '00000000000000000000000000000001 01 80 10 80 lo\n'
        fence = drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        self.assertEqual(len(fence.rules), 2)

    def test_duplicate_owned_comment_blocks_cleanup_without_deleting_anything(self):
        fence = drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        self.runner.rules['ip6tables'].append(self.runner.rules['ip6tables'][0])
        with self.assertRaises(drain.FenceError):
            drain.remove_owned_fence(self.runner, fence)
        self.assertEqual(len(self.runner.rules['iptables']), 1)
        self.assertEqual(len(self.runner.rules['ip6tables']), 2)

    def test_repeated_cleanup_is_safe_when_exact_owned_rules_already_absent(self):
        fence = drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        drain.remove_owned_fence(self.runner, fence)
        drain.remove_owned_fence(self.runner, fence)
        self.assertEqual(self.runner.rules, {'iptables': [], 'ip6tables': []})

    def test_incomplete_firewall_listing_cannot_claim_cleanup(self):
        fence = drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        self.runner.empty_listing = True
        with self.assertRaises(drain.FenceError):
            drain.remove_owned_fence(self.runner, fence)
        self.assertEqual(len(self.runner.rules['iptables']), 1)

    def test_pid_reuse_or_namespace_change_refuses_cleanup(self):
        for field, changed in [('identity', '42 (fixture) S ' + '0 ' * 18 + '99999 0\n'), ('netns', 'net:[888]\n')]:
            with self.subTest(field=field):
                self.runner = Firewall()
                fence = drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
                setattr(self.runner, field, changed)
                with self.assertRaises(drain.FenceError):
                    drain.remove_owned_fence(self.runner, fence)
                self.assertEqual(len(self.runner.rules['iptables']), 1)

    def test_tampered_rule_refuses_all_cleanup(self):
        fence = drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        self.runner.rules['ip6tables'][0] += ('-s', '::1')
        with self.assertRaises(drain.FenceError):
            drain.remove_owned_fence(self.runner, fence)
        self.assertEqual(len(self.runner.rules['iptables']), 1)

    def test_failed_verification_exposes_owned_fence_for_recovery(self):
        self.runner.drop_insert = True
        with self.assertRaises(drain.FenceError) as caught:
            drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        self.assertIsNotNone(caught.exception.fence)

    def test_partial_install_failure_preserves_ipv4_and_supports_exact_cleanup(self):
        self.runner.fail_ipv6_insert = True
        with self.assertRaises(drain.FenceError) as caught:
            drain.install_syn_fence(self.runner, 42, 9000, 'fixture-123')
        self.assertEqual(len(self.runner.rules['iptables']), 1)
        self.assertEqual(self.runner.rules['ip6tables'], [])
        drain.remove_owned_fence(self.runner, caught.exception.fence)
        self.assertEqual(self.runner.rules, {'iptables': [], 'ip6tables': []})

    def test_invalid_arguments_do_not_run_commands(self):
        for pid, port, token in [(0, 9000, 'ok'), (42, 0, 'ok'), (42, 9000, 'bad token'), (42, 9000, '')]:
            with self.assertRaises(ValueError):
                drain.install_syn_fence(self.runner, pid, port, token)
        self.assertEqual(self.runner.calls, [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
