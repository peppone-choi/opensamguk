"""Temporary fixtures only: terminal-ledger coverage is not product/runtime QA."""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest

import terminal_inventory as inventory


class TerminalInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.controller = inventory.API_ROOT / 'FixtureController.kt'
        self.write(self.controller, '''// @PostMapping("/not-a-route")
@RestController
@RequestMapping("/api/commands")
class FixtureController {
    @PostMapping(
        path = ["/court/{name}"]
    )
    fun submit() = Unit
    @GetMapping("/read")
    fun read() = Unit
}
''')
        self.write(Path('logic/Fixture.kt'), 'val terminal = "executionApplied"\n')
        self.catalog = {'inputs': [self.entry('court.queued')]}
        self.ledger = {
            'schemaVersion': 1,
            'families': [self.family('F5', ['court.queued'])],
            'inputOverrides': [],
            'outOfScopeMutationRoutes': [],
            'gaps': [],
        }

    @staticmethod
    def entry(input_id, kind='COURT_DECISION', phase='DECISION_TURN', state='HANDLER_READY'):
        return {'inputId': input_id, 'kind': kind, 'timing': {'phase': phase}, 'deliveryState': state}

    def family(self, family_id, inputs):
        return {
            'id': family_id, 'inputIds': inputs,
            'kinds': ['COURT_DECISION'], 'phases': ['DECISION_TURN'],
            'deliveryStates': ['HANDLER_READY'],
            'routes': [{'method': 'POST', 'path': '/api/commands/court/{name}',
                        'controller': str(self.controller)}],
            'admission': {'currentTypes': ['reservationAccepted'], 'target': 'RESERVATION_ACCEPTED/REJECTED'},
            'execution': {'currentTypes': ['executionApplied', 'executionRejected'], 'reason': 'issuer turn'},
            'effectTiming': 'issuer turn', 'invalidation': 'no cost; reason recorded',
            'consistencyClass': 'Mutation', 'status': 'CURRENT',
            'anchors': [{'path': 'logic/Fixture.kt', 'literal': '"executionApplied"'}],
        }

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')

    def check(self):
        self.write(inventory.CATALOG, json.dumps(self.catalog))
        self.write(inventory.LEDGER, inventory.BEGIN + '\n```json\n' +
                   json.dumps(self.ledger) + '\n```\n' + inventory.END)
        return inventory.check_root(self.root)

    def assertViolation(self, rule):
        result = self.check()
        self.assertTrue(any(v.startswith(rule + ':') for v in result['violations']), result)

    def test_normal_and_class_prefix(self):
        result = self.check()
        self.assertEqual([], result['violations'])
        self.assertEqual({'court.queued': 'F5'}, result['assignments'])
        self.assertEqual(1, len(result['mutationRoutes']))

    def test_new_input_id_even_with_existing_tuple_requires_inventory(self):
        self.catalog['inputs'].append(self.entry('court.new'))
        self.assertViolation('R1')

    def test_new_kind(self):
        self.catalog['inputs'][0]['kind'] = 'NEW_KIND'
        self.assertViolation('R2')

    def test_new_phase(self):
        self.catalog['inputs'][0]['timing']['phase'] = 'NEW_PHASE'
        self.assertViolation('R2')

    def test_new_mutation_route(self):
        self.write(self.controller, self.root.joinpath(self.controller).read_text() +
                   '\n@PostMapping("/new")\nfun newRoute() = Unit\n')
        self.assertViolation('R3')

    def test_missing_anchor_literal(self):
        self.write(Path('logic/Fixture.kt'), 'val terminal = "changed"')
        self.assertViolation('R4')

    def test_missing_anchor_path(self):
        self.root.joinpath('logic/Fixture.kt').unlink()
        self.assertViolation('R4')

    def test_duplicate_override(self):
        self.ledger['inputOverrides'] = [{'inputId': 'court.queued', 'familyId': 'F5'}] * 2
        self.assertViolation('R1')

    def test_override_precedes_tuple_but_requires_inventory_membership(self):
        immediate = self.family('F6', ['court.immediate'])
        immediate['routes'] = []
        self.ledger['families'].append(immediate)
        self.ledger['inputOverrides'] = [{'inputId': 'court.immediate', 'familyId': 'F6'}]
        self.catalog['inputs'].append(self.entry('court.immediate'))
        self.assertEqual([], self.check()['violations'])
        self.ledger['inputOverrides'][0]['familyId'] = 'F5'
        self.assertViolation('R1')

    def test_missing_override_does_not_silently_choose_by_membership(self):
        immediate = self.family('F6', ['court.immediate'])
        immediate['routes'] = []
        self.ledger['families'].append(immediate)
        self.catalog['inputs'].append(self.entry('court.immediate'))
        self.assertViolation('R1')

    def test_duplicate_input_membership(self):
        second = self.family('OTHER', ['court.queued'])
        second['routes'] = []
        self.ledger['families'].append(second)
        self.assertViolation('R1')

    def test_planned_never_counts_as_delivered(self):
        self.catalog['inputs'][0]['deliveryState'] = 'PLANNED'
        self.ledger['families'][0]['deliveryStates'] = ['PLANNED']
        self.assertViolation('R1')
        self.ledger['families'][0]['status'] = 'NOT_DELIVERED'
        self.assertEqual([], self.check()['violations'])

    def test_invalid_consistency_class_and_status(self):
        for key in ('consistencyClass', 'status'):
            original = self.ledger['families'][0][key]
            self.ledger['families'][0][key] = 'MADE_UP'
            self.assertViolation('R5')
            self.ledger['families'][0][key] = original

    def test_missing_required_nested_key_and_null_execution_reason(self):
        del self.ledger['families'][0]['admission']['target']
        self.assertViolation('R5')
        self.ledger['families'][0]['admission']['target'] = 'TARGET'
        self.ledger['families'][0]['execution'] = {'currentTypes': None, 'reason': ''}
        self.assertViolation('R5')

    def test_duplicate_route_and_stale_exclusion(self):
        route = copy.deepcopy(self.ledger['families'][0]['routes'][0])
        self.ledger['outOfScopeMutationRoutes'] = [{**route, 'reason': 'other surface'}]
        self.assertViolation('R3')
        self.ledger['outOfScopeMutationRoutes'][0]['path'] = '/not-existing'
        self.assertViolation('R3')

    def test_request_mapping_methods_and_multiple_paths(self):
        self.write(self.controller, '''@RequestMapping(value = "/api/commands")
class FixtureController {
 @RequestMapping(path = ["/court/{name}", "/alias"], method = [RequestMethod.POST, RequestMethod.PUT])
 fun write() = Unit
 @RequestMapping("/any")
 fun any() = Unit
}
''')
        routes = self.ledger['families'][0]['routes']
        routes[:] = [{**routes[0], 'method': method, 'path': '/api/commands' + path}
                     for method in ('POST', 'PUT') for path in ('/court/{name}', '/alias')] + [
                         {**routes[0], 'method': 'ANY', 'path': '/api/commands/any'}]
        self.assertEqual([], self.check()['violations'])

    def test_nested_data_class_preserves_prefix_and_literal_constant(self):
        self.write(self.controller, '''@RequestMapping("/api/commands")
class FixtureController {
 data class Request(val key: String)
 data class Other(val key: String) { fun helper() = Unit }
 @PostMapping(COURT_PATH)
 fun write() = Unit
 companion object { const val COURT_PATH = "/court/{name}" }
}
''')
        self.assertEqual([], self.check()['violations'])

    def test_unsupported_dynamic_mapping_fails_closed(self):
        self.write(self.controller, '@PostMapping(ROUTE_CONSTANT)\nfun write() = Unit')
        self.assertViolation('R3')

    def test_malformed_json_and_wrong_collection_type_are_failures(self):
        self.check()
        self.write(inventory.LEDGER, inventory.BEGIN + '\n```json\n{\n```\n' + inventory.END)
        self.assertTrue(inventory.check_root(self.root)['violations'])
        self.ledger['families'] = 'not-an-array'
        self.assertViolation('R5')

    def test_deterministic_json_and_cli_exit_status(self):
        self.check()
        outputs = []
        for _ in range(2):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = inventory.main(['--check', '--json', '--root', str(self.root)])
            self.assertEqual(0, code)
            outputs.append(out.getvalue())
        self.assertEqual(outputs[0], outputs[1])
        self.catalog['inputs'].append(self.entry('court.new'))
        self.check()
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, inventory.main(['--check', '--root', str(self.root)]))


if __name__ == '__main__':
    unittest.main()
