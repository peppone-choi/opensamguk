"""Bounded preserving consumer/mount contracts; no consumer creation or publication."""
import copy
from pathlib import Path

from game_server_recovery import SERVICES, checked_path, require

PAIRS = {'PUBLIC': ('spep-game-api', 'spep-web-game'),
         'PRIVATE': ('spep-game-api-validation', 'spep-web-game-validation')}


def exposure(obj):
    return {'ports': copy.deepcopy(obj['HostConfig'].get('PortBindings')),
            'exposed_ports': copy.deepcopy(obj['Config'].get('ExposedPorts')),
            'networks': {name: {'aliases': copy.deepcopy(n.get('Aliases')), 'network_id': n.get('NetworkID')}
                         for name, n in obj['NetworkSettings']['Networks'].items()}}


def environment(obj):
    result = {}
    for entry in obj['Config']['Env']:
        key, sep, value = entry.partition('=')
        require(sep and key and key not in result and '\0' not in entry, 'invalid duplicate effective env')
        result[key] = value
    return result


def observe(recovery):
    names = set(recovery.docker.run(['container', 'ls', '--all', '--format', '{{.Names}}']).decode().splitlines())
    modes = [mode for mode, pair in PAIRS.items() if set(pair) <= names and not (set(PAIRS['PRIVATE' if mode == 'PUBLIC' else 'PUBLIC']) & names)]
    require(len(modes) == 1, 'mixed/partial/stopped opposite preserving consumers refused')
    mode = modes[0]
    selected = {s: 'spep-' + s for s in SERVICES}
    selected.update(zip(('game-api', 'web-game'), PAIRS[mode]))
    before = {s: recovery.inspect('container', n) for s, n in selected.items()}
    for service, obj in before.items():
        require(obj['Name'] == '/' + selected[service] and obj['State']['Running'] is True and
                obj['State']['Status'] == 'running', 'preserving source not running')
    visible = {s: exposure(before[s]) for s in ('game-api', 'web-game')}
    if mode == 'PRIVATE':
        require(all(not v['ports'] and all(not n['aliases'] for n in v['networks'].values()) for v in visible.values()),
                'PRIVATE preserving ports/aliases must be empty')
        require(environment(before['web-game']).get('GAME_API_URL') == 'http://spep-game-api-validation:8081',
                'PRIVATE web upstream mismatch')
    topology = {'version': 1, 'mode': mode, 'names': selected, 'exposure': visible}
    validate(topology)
    return topology, before


def validate(topology):
    require(isinstance(topology, dict) and set(topology) == {'version', 'mode', 'names', 'exposure'} and
            type(topology['version']) is int and topology['version'] == 1 and topology['mode'] in PAIRS,
            'invalid preserving topology')
    names = {s: 'spep-' + s for s in SERVICES}
    names.update(zip(('game-api', 'web-game'), PAIRS[topology['mode']]))
    require(topology['names'] == names and set(topology['exposure']) == {'game-api', 'web-game'}, 'preserving topology inventory mismatch')
    for value in topology['exposure'].values():
        require(isinstance(value, dict) and set(value) == {'ports', 'exposed_ports', 'networks'} and
                isinstance(value['networks'], dict) and value['networks'] and all(
                    isinstance(n, dict) and set(n) == {'aliases', 'network_id'} and isinstance(n['network_id'], str) and n['network_id']
                    for n in value['networks'].values()), 'invalid preserving exposure')
        require(value['ports'] is None or isinstance(value['ports'], dict), 'invalid preserving port bindings')
        require(value['exposed_ports'] is None or isinstance(value['exposed_ports'], dict), 'invalid preserving exposed ports')
        for network in value['networks'].values():
            require(network['aliases'] is None or (isinstance(network['aliases'], list) and all(isinstance(a, str) for a in network['aliases'])), 'invalid preserving aliases')
        if topology['mode'] == 'PRIVATE':
            require(not value['ports'] and all(not n['aliases'] for n in value['networks'].values()), 'PRIVATE exposure invalid')


def mount(obj, destination):
    matches = [m for m in obj['Mounts'] if m.get('Destination') == destination]
    require(len(matches) == 1, 'missing/duplicate preserving mount destination')
    return matches[0]


def mounts(obj, service, stack, *, fullbundle):
    required = {'/data/scenarios': Path(stack) / 'data/scenarios'} if service in ('game-engine', 'game-api') else {}
    if service == 'game-api' and fullbundle:
        required['/app/data/map/topdown'] = Path(stack) / 'data/topdown/pep'
    require(len(obj['Mounts']) == len(required), 'extra/missing preserving mount')
    for destination, source in required.items():
        value = mount(obj, destination)
        require(value.get('Type') == 'bind' and value.get('Source') == str(source) and value.get('RW') is False,
                'preserving mount identity mismatch')
        path = checked_path(source, directory=True)
        require(path.stat().st_dev == Path(stack).stat().st_dev, 'foreign preserving mount filesystem')
    if service in ('game-engine', 'game-api'):
        require(environment(obj).get('SCENARIO_DIR') == '/data/scenarios', 'empty/bundled preserving scenario refused')


def assert_current(recovery, topology, *, running=None):
    validate(topology)
    names = set(recovery.docker.run(['container', 'ls', '--all', '--format', '{{.Names}}']).decode().splitlines())
    opposite = PAIRS['PRIVATE' if topology['mode'] == 'PUBLIC' else 'PUBLIC']
    require(not (set(opposite) & names), 'opposite preserving consumer appeared')
    for service in ('game-api', 'web-game'):
        obj = recovery.inspect('container', topology['names'][service])
        require(obj['Name'] == '/' + topology['names'][service] and exposure(obj) == topology['exposure'][service],
                'preserving exposure drift')
        if running is not None:
            require(obj['State']['Running'] is running, 'preserving consumer state drift')
