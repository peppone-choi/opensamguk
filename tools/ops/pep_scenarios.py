"""Approved reset IDs and packaged declarations; the JVM importer remains the build gate."""
import json
from pathlib import Path
import re

CATALOG = Path('app/gateway-api/src/main/resources/scenario-reset-catalog.json')
RESOURCES = Path('infra/src/main/resources/scenario')
CODE = re.compile(r'scenario_[0-9]+')


def approval(root):
    data = json.loads((root / CATALOG).read_text())
    codes = data.get('approvedCodes')
    if (data.get('schemaVersion') != 1 or not isinstance(codes, list) or not codes
            or any(not isinstance(c, str) or not CODE.fullmatch(c) for c in codes)
            or len(set(codes)) != len(codes) or data.get('defaultCode') not in codes):
        raise ValueError('invalid approved scenario catalog')
    return data


def scenario(root, code=None):
    catalog = approval(root)
    if code is None:
        code = catalog['defaultCode']
    if not isinstance(code, str) or code not in catalog['approvedCodes']:
        raise ValueError('unapproved scenario selection')
    path = root / RESOURCES / (code + '.json')
    if not path.is_file() or path.is_symlink():
        raise ValueError('selected scenario has no packaged prepared resource')
    try:
        data = json.loads(path.read_text())
        active = data['seedContract']['activeGenerals']
        prepared = (data.get('worldFormat') == 'GENERAL_RETAINER_CAMPAIGN'
                    and 'ruleProfile' not in data and isinstance(data.get('title'), str) and data['title'].strip()
                    and isinstance(data.get('nation'), list) and bool(data['nation'])
                    and isinstance(data.get('general'), list) and bool(data['general'])
                    and isinstance(data.get('rulers'), list) and len(data['rulers']) == len(data['nation'])
                    and isinstance(data.get('personPolicies'), list) and bool(data['personPolicies'])
                    and isinstance(data.get('warehouses'), dict)
                    and all(type(active.get(k)) is int and active[k] > 0 for k in ('base', 'extended')))
    except (OSError, ValueError, KeyError, TypeError):
        prepared = False
    if not prepared:
        raise ValueError('selected scenario is not a prepared product resource')
    return code, data['title']
