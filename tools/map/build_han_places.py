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
    '郡': ('COMMANDERY', 6), '国': ('COMMANDERY', 6), '尹': ('COMMANDERY', 6),
    '군': ('COMMANDERY', 6), '典农校尉': ('COMMANDERY', 6),
    '县': ('COUNTY', 5), '侯国': ('COUNTY', 5), '道': ('COUNTY', 5),
    '邑': ('COUNTY', 5),
}

# lv8 "특" = 왕조 수도. CHGIS에 수도 플래그가 없어 저작한 목록이다 — 고증 근거를 남긴다.
#   洛陽 후한/위 수도 · 許(허창) 196~220 헌제 파천지 · 成都 촉한 221~ · 建業 오 229~
#   長安 후한 190~195 동탁 천도지, 이후 위 서도(西都)
CAPITALS = {'雒阳县', '洛阳县', '许县', '许昌县', '成都县', '建业县', '长安县'}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--year', type=int, default=220)
    ap.add_argument('--grid', type=int, default=256)
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
            tier = TIER.get(r['TYPE_CH'])
            if tier is None:
                dropped[r['TYPE_CH']] += 1
                continue
            try:
                lon, lat = float(r['X_COOR']), float(r['Y_COOR'])
            except ValueError:
                dropped['좌표없음'] += 1
                continue
            kind, level = tier
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

    places = sorted(seats.values(), key=lambda p: (p['nameCh'], p['lon'], p['lat']))
    if not places:
        sys.exit(f'FATAL: {args.year}년 유효 치소 0건.')

    # --- 등적 투영: 위도 중심에서 경도를 cos(lat0)으로 눌러 실거리 종횡비를 맞춘다 ---
    lat0 = sum(p['lat'] for p in places) / len(places)
    k = math.cos(math.radians(lat0))
    px = [p['lon'] * k for p in places]
    py = [p['lat'] for p in places]
    x0, x1, y0, y1 = min(px), max(px), min(py), max(py)
    span = max(x1 - x0, y1 - y0)          # 정사각 격자 — 실측 종횡비 1.06이라 낭비가 없다
    pad = span * 0.02                     # 가장자리 도시가 격자 벽에 붙지 않도록
    span += pad * 2
    N = args.grid

    taken, nudged = {}, 0
    for p, sx, sy in zip(places, px, py):
        gx = min(N - 1, int((sx - x0 + pad) / span * N))
        gy = min(N - 1, int((y1 - sy + pad) / span * N))   # 북쪽이 gy=0
        if (gx, gy) in taken:
            gx, gy = free_cell(taken, gx, gy, N)
            nudged += 1
        taken[(gx, gy)] = p['id']
        p['gx'], p['gy'] = gx, gy

    doc = dict(
        source='CHGIS V6 (Harvard/Fudan) — 재배포 금지, ADR-LITE-039',
        year=args.year, grid=N, projection=dict(lat0=round(lat0, 4), k=round(k, 6),
        x0=round(x0, 6), y1=round(y1, 6), span=round(span, 6)),
        budgetClass='ADMINISTRATIVE_SETTLEMENT',
        count=len(places), nudged=nudged, places=places,
    )
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1, sort_keys=True)

    lv = Counter(p['level'] for p in places)
    print(f'{args.out}: {args.year}년 치소 {len(places)}개 → {N}x{N} 격자')
    print(f'  등급  ' + ' '.join(f'lv{l}:{lv[l]}' for l in sorted(lv)))
    print(f'  나눔  {nudged}개 (동일칸 충돌 → 결정론적 나선 이동)')
    if dropped:
        print(f'  제외  {dict(dropped.most_common(8))}')


def free_cell(taken, gx, gy, N):
    """빈 칸을 나선형으로 찾는다. 정렬된 입력 + 고정 순회 = 결정론적."""
    for radius in range(1, N):
        for dx in range(-radius, radius + 1):
            for dy in range(-radius, radius + 1):
                if max(abs(dx), abs(dy)) != radius:
                    continue
                nx, ny = gx + dx, gy + dy
                if 0 <= nx < N and 0 <= ny < N and (nx, ny) not in taken:
                    return nx, ny
    sys.exit('FATAL: 격자에 빈 칸이 없다. --grid 를 키워라.')


if __name__ == '__main__':
    main()
