#!/usr/bin/env python3
"""후한/삼국 행정 치소를 CHGIS V6에서 뽑아 게임 격자 좌표로 투영한다.

입력  data/chgis-source/v6_time_{pref,cnty}_pts_utf_wgs84.dbf   (gitignored, ADR-LITE-039)
출력  data/map/han-places.json                                   (gitignored, 재배포 금지)

이 스크립트만 버전 관리한다. 원본도 파생 좌표도 커밋하지 않는다.

담기는 것은 ADMINISTRATIVE_SETTLEMENT(州治/郡治/縣治)뿐이다.
적벽·관도·호로관 같은 STRATEGIC_NON_ADMINISTRATIVE와 한반도·왜 같은
EXTERNAL_PLACE는 CHGIS에 아예 없다(실측: docs/superpowers/research/
2026-08-18-chgis-coverage-and-place-taxonomy.md). 별도 출처·별도 파일이다.

usage:  python3 tools/map/build_han_places.py [--year 220] [--grid 256]

계약(#524, 2026-08-24 실측): CHGIS TYPE_CH는 郡/國 분류 근거가 아니다 — 정본 카탈로그
(data/curated/han/administrative-units.json, 續漢書 郡國志 기반, groups[].groupType)가
판정자다. 실측: CHGIS dbf를 직접 읽으면 TYPE_CH='国' 하나에 진짜 KINGDOM과 마을급
侯國(安眾/新成/征羌侯國), 屬國(犍為屬國), 심지어 이름이 '郡'인 것(樂安郡)까지 뒤섞여
있다. 반대로 常山/趙/中山/齊/北海/琅邪/梁/陳/下邳/河間/彭城 같은 진짜 KINGDOM 11개는
CHGIS dbf에서 다년 구간(예: 常山郡 220-582년) 동안 TYPE_CH='郡'|'侯国'로 잘못 적혀
있어 TYPE_CH만 믿으면 놓친다. `tools/map/*`의
다른 스크립트를 훑어봐도(2026-08-24) CHGIS TYPE_CH에서 KINGDOM/COMMANDERY를 직접
재유도하는 곳은 이 파일뿐이었다 — `build_external_places.py`는 수작업 리터럴,
`build_administrative_place_overlay.py`/`build_terrain_grid.py`는 이미 카탈로그
groupType이나 이 파일이 낸 kind를 그대로 신뢰한다.
"""
import argparse, json, math, os, struct, sys
from collections import Counter

SRC = 'data/chgis-source'
OUT = 'data/map/han-places.json'

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
# 한다. 실측(#524): 安眾/新成/征羌侯國(마을급 侯國)과 陳留(실제로는 COMMANDERY, 樂安은
# TYPE_CH='国'이지만 정본 카탈로그상 KINGDOM이라 이 목록엔 안 든다)가 전부 TYPE_CH='国'
# 하나로 뭉뚱그려지고, 거꾸로 常山/趙/中山/齊/北海/琅邪/梁/陳/下邳/河間/彭城(11개)처럼
# CHGIS dbf에서 다년 구간 동안 TYPE_CH='郡'|'侯国'로 잘못 적혀 있던 진짜 KINGDOM이
# COMMANDERY/COUNTY로 떨어진다. 續漢書 郡國志 정본 카탈로그
# (data/curated/han/administrative-units.json, groups[].groupType)로 되짚는다 —
# 이름 어간이 일치하면 CHGIS 표기 대신 정본이 이긴다.
GROUP_SUFFIX = '县縣國国郡州道邑'


def _stem(name):
    return name.rstrip(GROUP_SUFFIX) or name


def _load_group_kind(path=os.path.join('data', 'curated', 'han', 'administrative-units.json')):
    if not os.path.exists(path):
        return {}
    with open(path, encoding='utf-8') as fh:
        catalog = json.load(fh)
    idx = {}
    for g in catalog['groups']:
        for name in (g['canonicalGroup'], g.get('sourceGroupName')):
            if name:
                idx[_stem(name)] = g['groupType']
    return idx


GROUP_KIND = _load_group_kind()


def classify(type_ch, name_ch, name_ft, group_kind=None):
    """TYPE_CH -> (kind, level). group_kind 를 넘기지 않으면 GROUP_KIND(정본 카탈로그)를 쓴다.

    #524: CHGIS TYPE_CH만으로는 郡·國·侯國·屬國을 못 가른다(郡/国/侯国 표기가 뒤섞여 있다).
    TYPE_CH가 이 넷 중 하나면 정본 카탈로그로 되짚어 override한다. 카탈로그에 없는
    '国'/'侯国'는 마을급 侯國(안眾/新成/征羌/衛國류)로 보고 COUNTY로 둔다.
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
        elif type_ch in ('国', '侯国'):
            kind, level = 'COUNTY', 5
    return kind, level

# lv8 "특" = 왕조 수도. CHGIS에 수도 플래그가 없어 저작한 목록이다 — 고증 근거를 남긴다.
#   洛陽 후한/위 수도 · 許(허창) 196~220 헌제 파천지 · 成都 촉한 221~ · 建業 오 229~
#   長安 후한 190~195 동탁 천도지, 이후 위 서도(西都)
CAPITALS = {'雒阳县', '洛阳县', '许县', '许昌县', '成都县', '建业县', '长安县'}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--year', type=int, default=220)
    ap.add_argument('--grid', type=int, default=256)
    # 後漢 세계 프레임. 서 돈황(94.1E) · 동 왜 규슈(130.4E) · 남 象林/林邑(15.75N) ·
    # 북 부여(44.4N). 더 넓히면 필리핀·태국이 딸려와 중원이 쪼그라든다.
    ap.add_argument('--bbox', nargs=4, type=float, metavar=('LO_LON', 'HI_LON', 'LO_LAT', 'HI_LAT'),
                    default=[93.0, 133.0, 15.0, 45.0])
    ap.add_argument('--out', default=OUT)
    args = ap.parse_args()

    seats, dropped = {}, Counter()
    for layer in ('pref', 'cnty'):
        path = f'{SRC}/v6_time_{layer}_pts_utf_wgs84.dbf'
        if not os.path.exists(path):
            sys.exit(f'FATAL: {path} 없음. CHGIS V6 Dataverse 배포본을 {SRC}/ 에 풀어라.')
        for r in read_dbf(path):
            if not (as_int(r['BEG_YR'], -9999) <= args.year <= as_int(r['END_YR'], 9999)):
                continue
            classified = classify(r['TYPE_CH'], r['NAME_CH'], r['NAME_FT'])
            if classified is None:
                dropped[r['TYPE_CH']] += 1
                continue
            try:
                lon, lat = float(r['X_COOR']), float(r['Y_COOR'])
            except ValueError:
                dropped['좌표없음'] += 1
                continue
            kind, level = classified
            key = (r['NAME_CH'], round(lon, 4), round(lat, 4))
            prev = seats.get(key)
            # 같은 지점의 郡治 겸 縣治는 CHGIS에 두 번 실린다. 높은 등급을 남긴다.
            if prev and prev['level'] >= level:
                continue
            seats[key] = dict(
                id=r['SYS_ID'], nameCh=r['NAME_CH'], namePy=r['NAME_PY'],
                nameFt=r['NAME_FT'] or r['NAME_CH'], typeCh=r['TYPE_CH'],
                kind=kind, level=8 if r['NAME_CH'] in CAPITALS else level,
                lon=lon, lat=lat, presLoc=r['PRES_LOC'],
                begYr=as_int(r['BEG_YR'], -9999), endYr=as_int(r['END_YR'], 9999),
            )

    # CHGIS 밖 지점(交州 남부·한반도·소국)을 합류시킨다. 점만 찍는 게 아니라 소유 격자와
    # 도로망에 그대로 들어가야 한다 — 낙랑이 지도에만 있고 길이 없으면 갈 수가 없다.
    ext_path = os.path.join(os.path.dirname(args.out), 'external-places.json')
    external = []
    if os.path.exists(ext_path):
        for e in json.load(open(ext_path))['places']:
            if not (e['begYr'] <= args.year <= e['endYr']):
                continue
            e.setdefault('nameCh', e['nameFt'])
            seats[(e['nameFt'], round(e['lon'], 4), round(e['lat'], 4))] = e
            external.append(e['nameFt'])

    places = sorted(seats.values(), key=lambda p: (p['nameCh'], p['lon'], p['lat']))

    # CHGIS 는 같은 縣을 시기별·비정별로 여러 번 싣는다 — 盧氏縣 이 -100~1911 과 23~1911 로
    # 두 줄, 堂陽縣 이 -196~555 와 -144~264 로 두 줄이다. 220년 필터만으로는 둘 다 남아
    # 지도에 같은 이름이 나란히 두 번 찍힌다. 이름이 같고 서로 붙어 있으면(또는 오늘날
    # 비정지가 같으면) 한 점으로 접는다. 멀리 떨어진 동명은 진짜 별개라 남긴다 —
    # 汝南 上蔡 과 豫章 上蔡 은 다른 곳이다.
    # 어간이 같고 좌표가 같으면 같은 자리다 — CHGIS 는 彭城县 과 彭城国(侯國) 을 같은 좌표로
    # 따로 싣는다. 둘 다 縣 등급이면 행정 단위 쪽이 군더더기라 縣 을 남긴다. 한쪽이 郡
    # 등급이면 건드리지 않는다 — 그 점이 郡 승격의 근거다(下邳县/下邳郡).
    SUFFIX = '县縣國国郡州道邑'
    def stem(n):
        return n.rstrip(SUFFIX) or n

    NEAR_DEG = 0.5
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
            same_spot = (q.get('presLoc') and q.get('presLoc') == p.get('presLoc')) or (
                abs(q['lon'] - p['lon']) <= NEAR_DEG and abs(q['lat'] - p['lat']) <= NEAR_DEG)
            if same_spot:
                near = q
                break
        if near is False:
            continue          # 위에서 접었다
        if near is None:
            kept.append(p)
            continue
        # 220년을 더 좁게 감싸는 시기 조각을 남긴다. 그 시대에 더 특정된 기록이다.
        rank_p = (p['endYr'] - p['begYr'], str(p['id']))
        rank_q = (near['endYr'] - near['begYr'], str(near['id']))
        if rank_p[0] == rank_q[0]:
            ties.append(f"{p['nameFt']}({near['id']}·{p['id']})")
        loser = p if rank_q < rank_p else near
        if loser is near:
            kept[kept.index(near)] = p
        folded.append(loser['nameFt'])
    if folded:
        msg = f'  중복  붙어있는 동명 {len(folded)}곳 접음'
        if ties:
            msg += f' · 시기 동률 {len(ties)}건은 id 순으로 골랐다: {ties}'
        print(msg)
    places = kept

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
