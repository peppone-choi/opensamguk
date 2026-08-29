#!/usr/bin/env python3
"""후한/삼국 행정 치소를 CHGIS V6에서 뽑아 게임 격자 좌표로 투영한다.

입력  data/chgis-source/v6_time_{pref,cnty}_pts_utf_wgs84.dbf   (gitignored, ADR-LITE-039)
출력  data/map/han-places.json                                   (gitignored, 재배포 금지)

이 스크립트만 버전 관리한다. 원본도 파생 좌표도 커밋하지 않는다.

담기는 것은 ADMINISTRATIVE_SETTLEMENT(州治/郡治/縣治)뿐이다.
적벽·관도·호로관 같은 STRATEGIC_NON_ADMINISTRATIVE와 한반도·왜 같은
EXTERNAL_PLACE는 CHGIS에 아예 없다(실측: docs/superpowers/research/
2026-08-18-chgis-coverage-and-place-taxonomy.md). 별도 출처·별도 파일이다.

usage:  python3 tools/map/build_han_places.py [--year 220] [--grid 768]

인자를 생략한 실행은 커밋 가능한 Han 지도용 canonical 768×669 격자를 만든다.
연구·synthetic fixture는 `--grid 256`처럼 비정본 값을 명시할 수 있다.

계약(#524, 2026-08-24 실측): CHGIS TYPE_CH는 郡/國 분류 근거가 아니다 — 정본 카탈로그
(data/curated/han/administrative-units.json, 續漢書 郡國志 기반, groups[].groupType)가
판정자다. 실측: CHGIS dbf를 직접 읽으면 TYPE_CH='国' 하나에 진짜 KINGDOM과 마을급
侯國(安眾/新成/征羌侯國), 屬國(犍為屬國), 심지어 이름이 '郡'인 것(樂安郡)까지 뒤섞여
있다. 반대로 stable-ID 원장이 display를 國으로 승격하는 常山/趙/中山/齊/北海/琅邪/梁/陳/
下邳/河間/樂安 11개는 CHGIS 원본 이름이 郡이다(예: 常山郡 220-582년; 樂安郡은
TYPE_CH='国'). 彭城國은 display가 이미 國이지만 TYPE_CH='侯国'이므로 별도 stable-ID exact
classification guard로 KINGDOM을 지켜야 한다. `tools/map/*`의
다른 스크립트를 훑어봐도(2026-08-24) CHGIS TYPE_CH에서 KINGDOM/COMMANDERY를 직접
재유도하는 곳은 이 파일뿐이었다 — `build_external_places.py`는 수작업 리터럴,
`build_administrative_place_overlay.py`/`build_terrain_grid.py`는 이미 카탈로그
groupType이나 이 파일이 낸 kind를 그대로 신뢰한다.
"""
import argparse, json, math, os, struct, sys
from collections import Counter

from han_place_stable_id_adjudications import adjudicate_record, load_adjudications

SRC = 'data/chgis-source'
OUT = 'data/map/han-places.json'
DUPLICATE_ADJUDICATIONS = os.path.join(
    'data', 'curated', 'han', 'han-place-duplicate-adjudications-v1.json'
)

# --- DBF 리더 (의존성 없음. 좌표는 X_COOR/Y_COOR 필드에 있어 SHP는 불필요) ---

def read_dbf(path):
    with open(path, 'rb') as fh:
        buf = fh.read()
    nrec, hlen, rlen = struct.unpack('<IHH', buf[4:12])
    fields, off = [], 32
    while buf[off] != 0x0D:
        name = buf[off:off + 11].split(b'\0')[0].decode('ascii')
        size = buf[off + 16]
        fields.append((name, size))
        off += 32
    rows, base = [], hlen
    for i in range(nrec):
        rec = buf[base + i * rlen: base + (i + 1) * rlen]
        if rec[:1] == b'*':          # 삭제 표시 레코드
            continue
        row, p = {}, 1
        for name, size in fields:
            row[name] = rec[p:p + size].decode('utf-8', 'replace').strip()
            p += size
        rows.append(row)
    return rows


def as_int(s, default):
    try:
        return int(float(s))
    except (TypeError, ValueError):
        return default


# --- 등급 사다리 (ADR-LITE-038: 치소는 lv5~8. 縣治를 lv3 "관"으로 보내지 않는다) ---

TIER = {
    '州': ('PROVINCE', 7), '刺史部': ('PROVINCE', 7),
    # 續漢書 郡國志 卷113 「凡郡、國百五」: 郡과 國은 같은 1급(lv6)이지만 서로 다른 종류다.
    '郡': ('COMMANDERY', 6), '国': ('KINGDOM', 6), '尹': ('COMMANDERY', 6),
    '군': ('COMMANDERY', 6), '典农校尉': ('COMMANDERY', 6),
    '县': ('COUNTY', 5), '侯国': ('COUNTY', 5), '道': ('COUNTY', 5),
    '邑': ('COUNTY', 5),
}

# kind 오타가 파이프라인 끝까지 조용히 흘러가지 않도록 여기서 막는다.
ALLOWED_KIND = {'PROVINCE', 'COMMANDERY', 'KINGDOM', 'COUNTY'}
_bad_tier_kinds = {kind for kind, _ in TIER.values() if kind not in ALLOWED_KIND}
if _bad_tier_kinds:
    raise ValueError(f'unrecognized TIER kind(s): {sorted(_bad_tier_kinds)}')
del _bad_tier_kinds

# --- 郡/國 재판정 (#524): CHGIS TYPE_CH='国'/'侯国'/'郡'만으로는 郡·國·侯國·屬國을 구분 못
# 한다. 실측(#524): 安眾/新成/征羌侯國(마을급 侯國)과 陳留(실제로는 COMMANDERY)가
# TYPE_CH='国'으로 뭉뚱그려진다. 거꾸로 常山/趙/中山/齊/北海/琅邪/梁/陳/下邳/河間/樂安 11개는
# CHGIS 원본 이름의 郡을 display 國으로 승격해야 한다. 彭城國은 이미 國으로 표시되지만
# TYPE_CH='侯国'이라 COUNTY로 떨어지지 않도록 별도 stable-ID exact guard를 둔다. 續漢書 郡國志 정본 카탈로그
# (data/curated/han/administrative-units.json, groups[].groupType)로 되짚는다 —
# 이름 어간이 일치하면 CHGIS 표기 대신 정본이 이긴다.
GROUP_SUFFIX = '县縣國国郡州道邑'


def _stem(name):
    return name.rstrip(GROUP_SUFFIX) or name


def _load_group_kind(path=os.path.join('data', 'curated', 'han', 'administrative-units.json')):
    if not os.path.exists(path):
        raise FileNotFoundError(f'administrative-unit catalog missing: {path}')
    with open(path, encoding='utf-8') as fh:
        catalog = json.load(fh)
    idx = {}
    for g in catalog['groups']:
        for name in (g['canonicalGroup'], g.get('sourceGroupName')):
            if name:
                idx[_stem(name)] = g['groupType']
    if not idx:
        raise ValueError(f'administrative-unit catalog has no groups: {path}')
    return idx


GROUP_KIND = _load_group_kind()


def classify(type_ch, name_ch, name_ft, group_kind=None):
    """TYPE_CH -> (kind, level). group_kind 를 넘기지 않으면 GROUP_KIND(정본 카탈로그)를 쓴다.

    #524: CHGIS TYPE_CH만으로는 郡·國·侯國·屬國을 못 가른다(郡/国/侯国 표기가 뒤섞여 있다).
    TYPE_CH가 이 넷 중 하나면 정본 카탈로그로 되짚어 override한다. 카탈로그에 없는
    '国'은 이름만으로 COUNTY/KINGDOM을 추측할 수 없으므로 stable-ID 검토 원장을 요구한다.
    """
    if group_kind is None:
        group_kind = GROUP_KIND
    tier = TIER.get(type_ch)
    if tier is None:
        return None
    kind, level = tier
    if type_ch in ('郡', '国', '侯国', '尹', '典农校尉'):
        catalog_kind = group_kind.get(_stem(name_ft or name_ch))
        if catalog_kind:
            kind, level = catalog_kind, 6
        elif type_ch == '国':
            raise ValueError(
                f"ambiguous CHGIS TYPE_CH='国' requires stable-ID adjudication: "
                f"{name_ft or name_ch}"
            )
    return kind, level


def resolve_classification(row, layer, year, stable_entries, group_kind=None):
    """Resolve lifecycle, display identity, and tier before a row enters the build."""
    reviewed = adjudicate_record(row, layer, year, stable_entries)
    if reviewed is not None:
        if not reviewed['include']:
            return None
        return (
            reviewed['outputNameCh'], reviewed['outputNameFt'],
            reviewed['kind'], reviewed['level'], reviewed['begYr'], reviewed['endYr'],
        )
    beg_yr = as_int(row['BEG_YR'], -9999)
    end_yr = as_int(row['END_YR'], 9999)
    if not (beg_yr <= year <= end_yr):
        return None
    classified = classify(row['TYPE_CH'], row['NAME_CH'], row['NAME_FT'], group_kind)
    if classified is None:
        return None
    kind, level = classified
    return row['NAME_CH'], row['NAME_FT'] or row['NAME_CH'], kind, level, beg_yr, end_yr

# lv8 "특" = 왕조 수도. CHGIS에 수도 플래그가 없어 저작한 목록이다 — 고증 근거를 남긴다.
#   洛陽 후한/위 수도 · 許(허창) 196~220 헌제 파천지 · 成都 촉한 221~ · 建業 오 229~
#   長安 후한 190~195 동탁 천도지, 이후 위 서도(西都)
CAPITALS = {'雒阳县', '洛阳县', '许县', '许昌县', '成都县', '建业县', '长安县'}


# CHGIS duplicate grouping contract. The reviewed ledger deliberately describes upstream
# physical records; route/scenario artifacts are evidence, never runtime authorities here.
DUPLICATE_SUFFIX = '县縣國国郡州道邑'
NEAR_DEG = 0.5
EXPECTED_TRACKED_REVIEW_INPUTS = {
    'data/curated/han/route-node-location-adjudications-v1.json': {
        'path': 'data/curated/han/route-node-location-adjudications-v1.json',
        'sha256': 'f1c6c39607bbb8e48db3cf8a885a09594fbf01d983451005b74915e6d406af1a',
        'role': 'LOCATION_ADJUDICATION_REVIEW',
    },
    'data/curated/han/route-node-selection-v1.json': {
        'path': 'data/curated/han/route-node-selection-v1.json',
        'sha256': '9b96bf75f7e74adeebc87a8eb3e19e3c4c60dd6e3d87daaf6e824a4ad3dc007f',
        'role': 'ROUTE_NODE_SELECTION_REVIEW',
    },
}


def _require_exact_keys(value, expected, where):
    if not isinstance(value, dict):
        raise ValueError(f'{where}: object required')
    actual = set(value)
    if actual != set(expected):
        raise ValueError(
            f'{where}: exact keys required; missing={sorted(set(expected) - actual)}, '
            f'extra={sorted(actual - set(expected))}'
        )


def _require_int(value, where):
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f'{where}: integer required')


def validate_duplicate_adjudications(ledger):
    """Validate the generic, hand-reviewed upstream identity ledger fail-closed."""
    _require_exact_keys(
        ledger,
        {'schemaVersion', 'ledgerId', 'baselineYear', 'groupingContract',
         'trackedReviewInputs', 'adjudications'},
        'ledger',
    )
    if ledger['schemaVersion'] != 1:
        raise ValueError('ledger.schemaVersion must be 1')
    if ledger['ledgerId'] != 'han-place-duplicate-adjudications-v1':
        raise ValueError('ledger.ledgerId mismatch')
    if ledger['baselineYear'] != 220:
        raise ValueError('ledger.baselineYear must be 220')

    contract = ledger['groupingContract']
    _require_exact_keys(
        contract,
        {'nameRule', 'spatialRule', 'maxCoordinateDeltaDegrees', 'applicationOrder'},
        'ledger.groupingContract',
    )
    if contract != {
        'nameRule': 'EXACT_NAME_CH',
        'spatialRule': 'EQUAL_NONEMPTY_PRESENT_LOCATION_OR_AXIS_DELTA_AT_MOST',
        'maxCoordinateDeltaDegrees': NEAR_DEG,
        'applicationOrder': 'AFTER_REAL_GROUP_BEFORE_TEMPORAL_SPAN_FALLBACK',
    }:
        raise ValueError('ledger.groupingContract does not match builder semantics')

    tracked = ledger['trackedReviewInputs']
    if not isinstance(tracked, dict) or not tracked:
        raise ValueError('ledger.trackedReviewInputs must be a non-empty object')
    for key, item in tracked.items():
        _require_exact_keys(item, {'path', 'sha256', 'role'}, f'trackedReviewInputs[{key!r}]')
        if not isinstance(key, str) or not key or item['path'] != key:
            raise ValueError('tracked review input key must equal its non-empty path')
        digest = item['sha256']
        if (not isinstance(digest, str) or len(digest) != 64
                or any(ch not in '0123456789abcdef' for ch in digest)):
            raise ValueError(f'trackedReviewInputs[{key!r}].sha256 must be lowercase SHA-256')
    if tracked != EXPECTED_TRACKED_REVIEW_INPUTS:
        raise ValueError('ledger.trackedReviewInputs must equal the reviewed path/hash/role set')

    groups = ledger['adjudications']
    if not isinstance(groups, list):
        raise ValueError('ledger.adjudications must be an array')
    group_ids, member_ids, selected_ids = set(), set(), set()
    decisions = {}
    for group_index, group in enumerate(groups):
        where = f'adjudications[{group_index}]'
        _require_exact_keys(
            group, {'groupId', 'sourceNameCh', 'sourceNameFt', 'reviewState', 'members'}, where
        )
        group_id = group['groupId']
        if not isinstance(group_id, str) or not group_id or group_id in group_ids:
            raise ValueError(f'{where}.groupId must be a unique non-empty string')
        group_ids.add(group_id)
        for field in ('sourceNameCh', 'sourceNameFt'):
            if not isinstance(group[field], str) or not group[field]:
                raise ValueError(f'{where}.{field} must be a non-empty string')
        if group['reviewState'] != 'APPROVED_FOR_BUILD_SELECTION':
            raise ValueError(f'{where}.reviewState is not approved')
        members = group['members']
        if not isinstance(members, list) or len(members) < 2:
            raise ValueError(f'{where}.members must contain a duplicate group')
        group_member_ids, selected = set(), []
        for member_index, member in enumerate(members):
            member_where = f'{where}.members[{member_index}]'
            _require_exact_keys(
                member,
                {'physicalPlaceId', 'sourceNameCh', 'sourceNameFt', 'activeRange', 'coordinate',
                 'disposition', 'evidenceRefs'},
                member_where,
            )
            physical_id = member['physicalPlaceId']
            if (not isinstance(physical_id, str) or not physical_id.isdigit()
                    or physical_id in group_member_ids or physical_id in member_ids):
                raise ValueError(f'{member_where}.physicalPlaceId must be globally unique')
            group_member_ids.add(physical_id)
            member_ids.add(physical_id)
            for field in ('sourceNameCh', 'sourceNameFt'):
                if member[field] != group[field]:
                    raise ValueError(f'{member_where}.{field} must equal group {field}')
            active = member['activeRange']
            _require_exact_keys(active, {'begYr', 'endYr'}, f'{member_where}.activeRange')
            _require_int(active['begYr'], f'{member_where}.activeRange.begYr')
            _require_int(active['endYr'], f'{member_where}.activeRange.endYr')
            if active['begYr'] > active['endYr']:
                raise ValueError(f'{member_where}.activeRange is reversed')
            coordinate = member['coordinate']
            _require_exact_keys(coordinate, {'lon', 'lat'}, f'{member_where}.coordinate')
            for axis in ('lon', 'lat'):
                value = coordinate[axis]
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                    raise ValueError(f'{member_where}.coordinate.{axis} must be finite')
            if member['disposition'] not in {'SELECTED', 'REJECTED'}:
                raise ValueError(f'{member_where}.disposition is not recognized')
            if member['disposition'] == 'SELECTED':
                selected.append(physical_id)
            evidence = member['evidenceRefs']
            if (not isinstance(evidence, list)
                    or any(not isinstance(ref, str) for ref in evidence)
                    or len(evidence) != len(set(evidence))
                    or set(evidence) != set(EXPECTED_TRACKED_REVIEW_INPUTS)):
                raise ValueError(
                    f'{member_where}.evidenceRefs must reference every expected review input exactly once'
                )
        if len(selected) != 1:
            raise ValueError(f'{where} must have exactly one selected member')
        if selected[0] in selected_ids:
            raise ValueError(f'selected physicalPlaceId {selected[0]} occurs in multiple groups')
        selected_ids.add(selected[0])
        decisions[group_id] = selected[0]
    return decisions


def load_duplicate_adjudications(path=DUPLICATE_ADJUDICATIONS):
    with open(path, encoding='utf-8') as fh:
        ledger = json.load(fh)
    validate_duplicate_adjudications(ledger)
    return ledger


def _same_duplicate_spot(left, right):
    return bool(
        (left.get('presLoc') and left.get('presLoc') == right.get('presLoc'))
        or (abs(left['lon'] - right['lon']) <= NEAR_DEG
            and abs(left['lat'] - right['lat']) <= NEAR_DEG)
    )


def _reviewed_winners(places, year, ledger):
    """Bind reviewed members to the real active duplicate components in ``places``."""
    validate_duplicate_adjudications(ledger)
    by_id = {}
    for row in places:
        physical_id = str(row['id'])
        if physical_id in by_id:
            raise ValueError(f'duplicate source physicalPlaceId: {physical_id}')
        by_id[physical_id] = row

    winner_by_member = {}
    for group in ledger['adjudications']:
        declared_ids = {member['physicalPlaceId'] for member in group['members']}
        actual = []
        for member in group['members']:
            physical_id = member['physicalPlaceId']
            row = by_id.get(physical_id)
            if row is None:
                raise ValueError(f'{group["groupId"]}: reviewed member {physical_id} absent')
            active = member['activeRange']
            if not active['begYr'] <= year <= active['endYr']:
                raise ValueError(f'{group["groupId"]}: reviewed member {physical_id} inactive at {year}')
            coordinate = member['coordinate']
            if (row.get('nameCh') != member['sourceNameCh']
                    or row.get('nameFt') != member['sourceNameFt']
                    or row.get('begYr') != active['begYr']
                    or row.get('endYr') != active['endYr']
                    or row.get('lon') != coordinate['lon']
                    or row.get('lat') != coordinate['lat']):
                raise ValueError(f'{group["groupId"]}: reviewed member {physical_id} source drift')
            actual.append(row)
        source_names = {row['nameCh'] for row in actual}
        if len(source_names) != 1:
            raise ValueError(f'{group["groupId"]}: members are not a same-name group')

        # Compute the complete real grouping component, so an omitted third candidate or a
        # rejected record outside the selected member's component fails rather than falling back.
        component_ids = {str(actual[0]['id'])}
        changed = True
        while changed:
            changed = False
            for candidate in places:
                candidate_id = str(candidate['id'])
                if candidate_id in component_ids or candidate['nameCh'] not in source_names:
                    continue
                if any(_same_duplicate_spot(candidate, by_id[current]) for current in component_ids):
                    component_ids.add(candidate_id)
                    changed = True
        if component_ids != declared_ids:
            raise ValueError(
                f'{group["groupId"]}: reviewed members do not equal real duplicate group '
                f'(declared={sorted(declared_ids)}, actual={sorted(component_ids)})'
            )
        selected_id = next(
            member['physicalPlaceId'] for member in group['members']
            if member['disposition'] == 'SELECTED'
        )
        for physical_id in declared_ids:
            winner_by_member[physical_id] = selected_id
    return winner_by_member


def fold_duplicate_places(places, year, ledger):
    """Fold real duplicate groups, applying reviewed identity before the legacy fallback."""
    places = sorted(places, key=lambda p: (p['nameCh'], p['lon'], p['lat']))
    winner_by_member = _reviewed_winners(places, year, ledger)

    def stem(name):
        return name.rstrip(DUPLICATE_SUFFIX) or name

    kept, folded, ties = [], [], []
    for p in places:
        near = None
        for q in kept:
            if q['nameCh'] != p['nameCh']:
                if (q['kind'] == 'COUNTY' and p['kind'] == 'COUNTY'
                        and stem(q['nameCh']) == stem(p['nameCh'])
                        and abs(q['lon'] - p['lon']) <= 0.02 and abs(q['lat'] - p['lat']) <= 0.02):
                    loser = p if q.get('typeCh') == '县' else q
                    if loser is q:
                        kept[kept.index(q)] = p
                    folded.append(loser['nameFt'])
                    near = False
                    break
                continue
            if _same_duplicate_spot(q, p):
                near = q
                break
        if near is False:
            continue
        if near is None:
            kept.append(p)
            continue

        reviewed_p = winner_by_member.get(str(p['id']))
        reviewed_q = winner_by_member.get(str(near['id']))
        if reviewed_p is not None or reviewed_q is not None:
            if reviewed_p is None or reviewed_q is None or reviewed_p != reviewed_q:
                raise ValueError('reviewed duplicate group conflicted with runtime grouping')
            loser = near if str(p['id']) == reviewed_p else p
        else:
            # Existing deterministic fallback: retain the interval that more tightly spans year.
            rank_p = (p['endYr'] - p['begYr'], str(p['id']))
            rank_q = (near['endYr'] - near['begYr'], str(near['id']))
            if rank_p[0] == rank_q[0]:
                ties.append(f"{p['nameFt']}({near['id']}·{p['id']})")
            loser = p if rank_q < rank_p else near
        if loser is near:
            kept[kept.index(near)] = p
        folded.append(loser['nameFt'])
    return kept, folded, ties


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--year', type=int, default=220)
    ap.add_argument(
        '--grid', type=int, default=768,
        help='grid columns (canonical default: 768; other explicit values are research/synthetic only)',
    )
    # 後漢 세계 프레임. 서 돈황(94.1E) · 동 왜 규슈(130.4E) · 남 象林/林邑(15.75N) ·
    # 북 부여(44.4N). 더 넓히면 필리핀·태국이 딸려와 중원이 쪼그라든다.
    ap.add_argument('--bbox', nargs=4, type=float, metavar=('LO_LON', 'HI_LON', 'LO_LAT', 'HI_LAT'),
                    default=[93.0, 133.0, 15.0, 45.0])
    ap.add_argument('--out', default=OUT)
    args = ap.parse_args()

    seats, dropped = {}, Counter()
    stable_entries = load_adjudications()
    for layer in ('pref', 'cnty'):
        path = f'{SRC}/v6_time_{layer}_pts_utf_wgs84.dbf'
        if not os.path.exists(path):
            sys.exit(f'FATAL: {path} 없음. CHGIS V6 Dataverse 배포본을 {SRC}/ 에 풀어라.')
        for r in read_dbf(path):
            resolved = resolve_classification(r, layer, args.year, stable_entries)
            if resolved is None:
                dropped[r['TYPE_CH']] += 1
                continue
            try:
                lon, lat = float(r['X_COOR']), float(r['Y_COOR'])
            except ValueError:
                dropped['좌표없음'] += 1
                continue
            name_ch, name_ft, kind, level, beg_yr, end_yr = resolved
            key = (name_ch, round(lon, 4), round(lat, 4))
            prev = seats.get(key)
            # 같은 지점의 郡治 겸 縣治는 CHGIS에 두 번 실린다. 높은 등급을 남긴다.
            if prev and prev['level'] >= level:
                continue
            seats[key] = dict(
                id=r['SYS_ID'], nameCh=name_ch, namePy=r['NAME_PY'],
                nameFt=name_ft, typeCh=r['TYPE_CH'],
                kind=kind, level=8 if name_ch in CAPITALS else level,
                lon=lon, lat=lat, presLoc=r['PRES_LOC'],
                begYr=beg_yr, endYr=end_yr,
            )

    # CHGIS 밖 지점(交州 남부·한반도·소국)을 합류시킨다. 점만 찍는 게 아니라 소유 격자와
    # 도로망에 그대로 들어가야 한다 — 낙랑이 지도에만 있고 길이 없으면 갈 수가 없다.
    ext_path = os.path.join(os.path.dirname(args.out), 'external-places.json')
    external = []
    if os.path.exists(ext_path):
        with open(ext_path, encoding='utf-8') as fh:
            external_rows = json.load(fh)['places']
        for e in external_rows:
            if not (e['begYr'] <= args.year <= e['endYr']):
                continue
            e.setdefault('nameCh', e['nameFt'])
            seats[(e['nameFt'], round(e['lon'], 4), round(e['lat'], 4))] = e
            external.append(e['nameFt'])

    places = sorted(seats.values(), key=lambda p: (p['nameCh'], p['lon'], p['lat']))

    # CHGIS 는 같은 縣을 시기별·비정별로 여러 번 싣는다. 이름이 같고 서로 붙어
    # 있는 실제 duplicate group을 먼저 확인한 뒤, 검토된 upstream identity 선택을
    # 기존 시기 범위 fallback보다 앞에 적용한다. 미검토 group의 기존 결정성은 그대로다.
    ledger = load_duplicate_adjudications()
    places, folded, ties = fold_duplicate_places(places, args.year, ledger)
    if folded:
        msg = f'  중복  붙어있는 동명 {len(folded)}곳 접음'
        if ties:
            msg += f' · 시기 동률 {len(ties)}건은 id 순으로 골랐다: {ties}'
        print(msg)
    if not places:
        sys.exit(f'FATAL: {args.year}년 유효 치소 0건.')

    # --- 등적 투영: 위도 중심에서 경도를 cos(lat0)으로 눌러 실거리 종횡비를 맞춘다 ---
    # 프레임은 지점 분포가 아니라 세계 범위로 정한다. 지점에서 뽑으면 CHGIS 커버리지가
    # 곧 세계가 되어버린다 — 한반도·왜·교주 남부는 땅덩어리조차 안 그려진다.
    lo_lon, hi_lon, lo_lat, hi_lat = args.bbox
    lat0 = (lo_lat + hi_lat) / 2
    k = math.cos(math.radians(lat0))
    px = [p['lon'] * k for p in places]
    py = [p['lat'] for p in places]
    x0, x1, y0, y1 = lo_lon * k, hi_lon * k, lo_lat, hi_lat
    # 정사각 격자를 강제하지 않는다. 강제하면 짧은 축에 프레임 밖 여백이 붙어 —
    # 이 프레임에선 남쪽에 4도 넘게 — 필리핀까지 딸려 들어온다.
    pad = max(x1 - x0, y1 - y0) * 0.02
    cell = (x1 - x0 + pad * 2) / args.grid     # 등적이라 x·y 셀 크기가 같다
    cols, rows = args.grid, round((y1 - y0 + pad * 2) / cell)
    N = cols
    out_of_frame = [p['nameFt'] for p, sx, sy in zip(places, px, py)
                    if not (x0 <= sx <= x1 and y0 <= sy <= y1)]
    if out_of_frame:
        print(f'  경고  프레임 밖 {len(out_of_frame)}곳: {out_of_frame[:5]}')

    taken, nudged = {}, 0
    for p, sx, sy in zip(places, px, py):
        gx = min(cols - 1, int((sx - x0 + pad) / cell))
        gy = min(rows - 1, int((y1 + pad - sy) / cell))   # 북쪽이 gy=0
        if (gx, gy) in taken:
            gx, gy = free_cell(taken, gx, gy, cols, rows)
            nudged += 1
        taken[(gx, gy)] = p['id']
        p['gx'], p['gy'] = gx, gy

    doc = dict(
        source='CHGIS V6 (Harvard/Fudan) — 재배포 금지, ADR-LITE-039',
        year=args.year, grid=N, cols=cols, rows=rows,
        projection=dict(lat0=round(lat0, 4), k=round(k, 6), x0=round(x0, 6),
                        y1=round(y1, 6), pad=round(pad, 6), cell=round(cell, 8),
                        cols=cols, rows=rows),
        budgetClass='ADMINISTRATIVE_SETTLEMENT',
        count=len(places), nudged=nudged, places=places,
    )
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1, sort_keys=True)

    lv = Counter(p['level'] for p in places)
    print(f'{args.out}: {args.year}년 치소 {len(places)}개 → {cols}x{rows} 격자')
    print(f'  등급  ' + ' '.join(f'lv{l}:{lv[l]}' for l in sorted(lv)))
    print(f'  나눔  {nudged}개 (동일칸 충돌 → 결정론적 나선 이동)')
    if external:
        print(f'  합류  CHGIS 밖 {len(external)}곳: ' + ' '.join(external[:6]) + ' …')
    if dropped:
        print(f'  제외  {dict(dropped.most_common(8))}')


def free_cell(taken, gx, gy, cols, rows):
    """빈 칸을 나선형으로 찾는다. 정렬된 입력 + 고정 순회 = 결정론적."""
    for radius in range(1, max(cols, rows)):
        for dx in range(-radius, radius + 1):
            for dy in range(-radius, radius + 1):
                if max(abs(dx), abs(dy)) != radius:
                    continue
                nx, ny = gx + dx, gy + dy
                if 0 <= nx < cols and 0 <= ny < rows and (nx, ny) not in taken:
                    return nx, ny
    sys.exit('FATAL: 격자에 빈 칸이 없다. --grid 를 키워라.')


if __name__ == '__main__':
    main()
