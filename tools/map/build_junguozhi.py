#!/usr/bin/env python3
"""後漢書(續漢書) 郡國志에서 州-郡-縣 소속 계층과 戶口·지형 태그를 뽑는다.

입력  data/chgis-source/junguozhi/{yi,er,san,si,wu}.html   (ctext.org 사본, gitignored)
      data/chgis-source/v6_time_cnty_pts_utf_wgs84.dbf     (縣名 사전, gitignored)
출력  data/map/junguozhi.json                              (gitignored)

원문 규칙 두 개를 그대로 쓴다:
  「凡縣名先書者，郡所治也」  → 郡 블록의 첫 縣이 郡治다.
  縣 항목은 「縣名+註」이고 註는 。로 끝난다 → 縣은 블록 처음 또는 。 직후에서 시작한다.

城數(郡國志가 명시한 속현 수)를 파싱 체크섬으로 쓴다. 불일치는 숨기지 않고 보고한다.
屬國都尉는 원래 城數가 없으므로 체크섬 대상에서 제외한다.

usage:  python3 tools/map/build_junguozhi.py
"""
import glob, html, json, math, os, re, struct, sys
from collections import Counter

SRC = 'data/chgis-source'
OUT = 'data/map/junguozhi.json'
VOLS = ['yi', 'er', 'san', 'si', 'wu']

NUM = {'一':1,'二':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9}

def cn_int(s):
    """漢數字 → int. 郡國志 범위(1~數十萬)만 다룬다."""
    if not s:
        return None
    total, section, n = 0, 0, 0
    for ch in s:
        if ch in NUM:
            n = NUM[ch]
        elif ch == '十':
            section += (n or 1) * 10; n = 0
        elif ch == '百':
            section += (n or 1) * 100; n = 0
        elif ch == '千':
            section += (n or 1) * 1000; n = 0
        elif ch in ('萬', '万'):
            total += (section + n) * 10000; section = n = 0
        else:
            return None
    return total + section + n


def read_segments():
    seg_re = re.compile(r'<td class="ctext">\s*(?:<div id="comm\d+"></div>)?(.*?)</td>', re.S)
    out = []
    for v in VOLS:
        path = f'{SRC}/junguozhi/{v}.html'
        if not os.path.exists(path):
            sys.exit(f'FATAL: {path} 없음. ctext.org/hou-han-shu/jun-guo-{v}/zh 를 받아 두어라.')
        raw = open(path, encoding='utf-8', errors='replace').read()
        for m in seg_re.finditer(raw):
            t = html.unescape(re.sub(r'<[^>]+>', '', m.group(1))).strip()
            if t:
                out.append((v, t))
    return out


def chgis_points(layer, field='NAME_FT'):
    """漢代(-206~280) 존속 지점의 繁體名 → [(lon, lat)] 사전."""
    path = f'{SRC}/v6_time_{layer}_pts_utf_wgs84.dbf'
    with open(path, 'rb') as fh:
        buf = fh.read()
    nrec, hlen, rlen = struct.unpack('<IHH', buf[4:12])
    fields, off = [], 32
    while buf[off] != 0x0D:
        fields.append((buf[off:off+11].split(b'\0')[0].decode('ascii'), buf[off+16]))
        off += 32
    idx = {n: i for i, (n, _) in enumerate(fields)}
    out = {}
    for i in range(nrec):
        rec = buf[hlen + i*rlen: hlen + (i+1)*rlen]
        if rec[:1] == b'*':
            continue
        p2, vals = 1, []
        for _, size in fields:
            vals.append(rec[p2:p2+size].decode('utf-8', 'replace').strip()); p2 += size
        try:
            beg, end = int(float(vals[idx['BEG_YR']])), int(float(vals[idx['END_YR']]))
            lon, lat = float(vals[idx['X_COOR']]), float(vals[idx['Y_COOR']])
        except ValueError:
            continue
        if end < -206 or beg > 280:
            continue
        nm = vals[idx[field]] or vals[idx['NAME_CH']]
        for suf in ('縣', '县', '侯國', '侯国', '郡', '國', '国', '尹', '道', '邑'):
            if nm.endswith(suf) and len(nm) > len(suf):
                nm = nm[:-len(suf)]; break
        out.setdefault(nm, []).append((lon, lat))
    return out


def km(a, b):
    return math.hypot((a[0]-b[0]) * math.cos(math.radians((a[1]+b[1])/2)), a[1]-b[1]) * 111.0


def county_lexicon():
    """CHGIS 縣급 레이어의 漢代 縣名(繁體)을 사전으로 쓴다. 접미 縣/侯國/道/邑 은 뗀다."""
    path = f'{SRC}/v6_time_cnty_pts_utf_wgs84.dbf'
    with open(path, 'rb') as fh:
        buf = fh.read()
    nrec, hlen, rlen = struct.unpack('<IHH', buf[4:12])
    fields, off = [], 32
    while buf[off] != 0x0D:
        fields.append((buf[off:off+11].split(b'\0')[0].decode('ascii'), buf[off+16]))
        off += 32
    idx = {n: i for i, (n, _) in enumerate(fields)}
    names = set()
    for i in range(nrec):
        rec = buf[hlen + i*rlen: hlen + (i+1)*rlen]
        if rec[:1] == b'*':
            continue
        p, vals = 1, []
        for _, size in fields:
            vals.append(rec[p:p+size].decode('utf-8', 'replace').strip()); p += size
        try:
            beg, end = int(float(vals[idx['BEG_YR']])), int(float(vals[idx['END_YR']]))
        except ValueError:
            continue
        if end < -206 or beg > 280:      # 漢代에 존재하지 않은 縣은 사전에서 뺀다
            continue
        # ctext 원문은 繁體다. CHGIS NAME_CH 는 簡體이므로 NAME_FT 를 쓴다.
        nm = vals[idx['NAME_FT']] or vals[idx['NAME_CH']]
        for suf in ('縣', '县', '侯國', '侯国', '道', '邑', '國', '国'):
            if nm.endswith(suf) and len(nm) > len(suf):
                nm = nm[:-len(suf)]; break
        if 1 <= len(nm) <= 4:
            names.add(nm)
    return names


NOTE_START = re.compile(
    r'(有|又|或|一曰|今|古|故|本|秦|周|漢|春秋|世祖|光武|王莽|莽曰|詩|凡|其|右|'
    r'刺史治|都尉治|尉治|侯國|邑|[一-鿿]{1,3}水出|[一-鿿]{1,3}水|出)')

HEAD = re.compile(r'^[一-鿿]{1,7}(郡|國|尹|翊|風|屬國|都尉|校尉)$')
# 「侯國」「故國」처럼 註 안의 낱말이 헤더로 오인되지 않도록, 다음 세그먼트가
# 城數·戶口·雒陽거리로 시작하는지를 구조 검증으로 요구한다.
HEAD_NEXT = re.compile(r'([一二三四五六七八九十百]{1,4}城[，。]|戶[一二三四五六七八九十百]|口[一二三四五六七八九十百]|候官|雒陽[東西南北]|刺史治|都尉治)')
# 註 안의 낱말이 헤더로 오인되는 것을 막는다 (「侯國」「故國」…).
NOT_HEAD = {'侯國', '故國', '本國', '父國', '國', '屬國', '都尉', '校尉', '故郡'}
STATS = re.compile(r'^([一-鿿]*)城[，。]')
HU = re.compile(r'戶([一-鿿]+?)，口([一-鿿]+?)。')
DIST = re.compile(r'雒陽(東|西|南|北|東北|東南|西北|西南)?([一-鿿]+?)里')

# 지형·자원 태그: 註에서 직접 뽑는다. 밸런싱 수치를 지어내지 않기 위한 근거다.
TAGS = [('IRON', r'有鐵'), ('SALT', r'有鹽'), ('MOUNTAIN', r'有([一-鿿]{1,3})山'),
        ('RIVER_SOURCE', r'([一-鿿]{1,3})水出'), ('MARSH', r'有[一-鿿]{0,3}[澤陂湖]'),
        ('PASS', r'有([一-鿿]{1,3})關'), ('FORD', r'有([一-鿿]{1,3})津'),
        ('OLD_CITY', r'有([一-鿿]{1,3})城'), ('HAMLET', r'有([一-鿿]{1,3})聚'),
        ('PAVILION', r'有([一-鿿]{1,3})亭')]


CANON_105 = (
    '河南尹 河內郡 河東郡 弘農郡 京兆尹 左馮翊 右扶風 '                     # 司隸
    '潁川郡 汝南郡 梁國 沛國 陳國 魯國 '                                  # 豫州
    '魏郡 鉅鹿郡 常山國 中山國 安平國 河閒國 清河國 趙國 勃海郡 '          # 冀州
    '陳留郡 東郡 東平國 任城國 泰山郡 濟北國 山陽郡 濟陰郡 '              # 兗州
    '東海郡 琅邪國 彭城國 廣陵郡 下邳國 '                                # 徐州
    '濟南國 平原郡 樂安國 北海國 東萊郡 齊國 '                            # 青州
    '南陽郡 南郡 江夏郡 零陵郡 桂陽郡 武陵郡 長沙郡 '                     # 荊州
    '九江郡 丹陽郡 廬江郡 會稽郡 吳郡 豫章郡 '                            # 揚州
    '漢中郡 巴郡 廣漢郡 蜀郡 犍為郡 牂牁郡 越巂郡 益州郡 永昌郡 '
    '廣漢屬國 蜀郡屬國 犍為屬國 '                                        # 益州
    '隴西郡 漢陽郡 武都郡 金城郡 安定郡 北地郡 武威郡 張掖郡 酒泉郡 敦煌郡 '
    '張掖屬國 張掖居延屬國 '                                             # 涼州
    '上黨郡 太原郡 上郡 西河郡 五原郡 雲中郡 定襄郡 鴈門郡 朔方郡 '        # 并州
    '涿郡 廣陽郡 代郡 上谷郡 漁陽郡 右北平郡 遼西郡 遼東郡 玄菟郡 樂浪郡 遼東屬國 '  # 幽州
    '南海郡 蒼梧郡 鬱林郡 合浦郡 交趾郡 九真郡 日南郡'                    # 交州
).split()


def main():
    segs = read_segments()
    lex = county_lexicon()
    cnty_xy = chgis_points('cnty')
    pref_xy = chgis_points('pref')
    MAX_KM = 400.0        # 縣은 자기 郡治 근처에 있다. 원거리 동명 縣은 오탐이다.

    # 1) ctext가 漢數字를 세그먼트 중간에서 자른다: 「十五」+「城，戶…」 → 병합
    merged = []
    for vol, t in segs:
        if (merged and merged[-1][0] == vol and re.match(r'^[一二三四五六七八九十百]{0,4}城[，。]', t)
                and re.fullmatch(r'[一二三四五六七八九十百]{1,4}', merged[-1][1])):
            merged[-1] = (vol, merged[-1][1] + t)
        else:
            merged.append((vol, t))

    # 州 요약문 꼬리에 붙어 온 郡 헤더를 떼어낸다
    split = []
    for vol, t in merged:
        if '。' in t and HEAD.match(t.rsplit('。', 1)[-1]):
            head, tail = t.rsplit('。', 1)
            split.append((vol, head + '。')); split.append((vol, tail))
        else:
            split.append((vol, t))
    merged = split

    # 2) 郡 헤더로 블록 분할
    blocks, cur = [], None
    for i, (vol, t) in enumerate(merged):
        # 헤더 뒤에 연혁 註가 먼저 오는 郡도 있다(廣陽郡→「世祖省并上谷…」) → 3세그먼트 전방 탐색
        look = ''.join(x for _, x in merged[i + 1:i + 5])[:140]
        if HEAD.match(t) and t not in NOT_HEAD and HEAD_NEXT.search(look):
            cur = dict(vol=vol, jun=t, body=[])
            blocks.append(cur)
        elif cur is not None:
            cur['body'].append(t)
    if not blocks:
        sys.exit('FATAL: 郡 블록 0건. ctext 사본이 깨졌다.')

    for b in blocks:
        b['text'] = '\x1f'.join(b['body'])
        m = STATS.search(b['text'][:40])
        b['cities'] = cn_int(m.group(1)) if m else None
        hu = HU.search(b['text'][:120])
        b['hu'] = (cn_int(hu.group(1)), cn_int(hu.group(2))) if hu else (None, None)
        di = DIST.search(b['text'][:120])
        b['dist'] = (cn_int(di.group(2)), di.group(1)) if di else (None, None)
        b['rest'] = b['text'][(hu.end() if hu else (m.end() if m else 0)):]
        key = re.sub(r'(郡|國|尹|屬國|都尉|校尉)$', '', b['jun'])
        b['anchor'] = (pref_xy.get(key) or [None])[0]
        b['assigned'] = []

    # 3) PASS A — CHGIS 縣名을 블록 본문에서 찾는다. 이름을 잘라내지 않으므로 날조가 없다.
    #    매칭은 縣 시작점(블록 처음 · 。 직후 · 앞 縣 직후)에서만 인정한다.
    #    註 안 우연 일치(有前亭 의 「前亭」)를 이 규칙이 걸러낸다.
    for b in blocks:
        rest, seen = b['rest'], []
        n = len(rest)
        starts = [0] + [k + 1 for k, ch in enumerate(rest) if ch in '。\x1f']
        residual = []
        for s0 in starts:
            q = s0
            while q < n:
                hit = next((rest[q:q+L] for L in (4, 3, 2, 1) if rest[q:q+L] in cnty_xy), None)
                if not hit:
                    break
                seen.append((q, hit))
                q += len(hit)
            if q < n:
                residual.append((q, head_word(rest, q)))
        b['residual'] = [(q, w) for q, w in residual if w]
        # 같은 縣이 여러 번 나오면 첫 등장만 (郡治 판정이 첫 등장 순서에 달려 있다)
        first, order = {}, []
        for pos, nm in sorted(seen):
            if nm not in first:
                first[nm] = pos; order.append(nm)
        cand = []
        for nm in order:
            pts = cnty_xy[nm]
            d = min((km(b['anchor'], q) for q in pts), default=None) if b['anchor'] else None
            if d is not None and d > MAX_KM:
                continue
            cand.append(dict(name=nm, pos=first[nm], dist=d if d is not None else 9e9,
                             lon=pts[0][0], lat=pts[0][1], resolution='RESOLVED_POINT'))
        # 城數 초과분은 郡治에서 가장 먼 것부터 버린다 (원문이 정한 수가 상한이다)
        if b['cities'] and len(cand) > b['cities']:
            keep = sorted(sorted(cand, key=lambda c: c['dist'])[:b['cities']], key=lambda c: c['pos'])
            cand = keep
        b['assigned'] = cand

    # 4) PASS B — 城數에 모자란 몫은 원문에서만 존재하는 縣이다(CHGIS 결손).
    #    좌표를 지어내지 않고 CANDIDATE_REGION 으로 남긴다 (spec 2026-07-13 :291).
    out, gap_total = [], 0
    for b in blocks:
        got = sorted(b['assigned'], key=lambda c: c['pos'])
        for c in got:
            c.pop('dist', None)
        gap = (b['cities'] - len(got)) if b['cities'] else 0
        extra = []
        if gap > 0:
            known = {c['name'] for c in got}
            for _, w in sorted(b['residual']):
                if w in known or len(extra) >= gap:
                    continue
                known.add(w)
                extra.append(dict(name=w, note='', tags=[], lon=None, lat=None,
                                  resolution='CANDIDATE_REGION'))
        for c in got:
            end = min([k for k in (b['rest'].find(ch, c['pos'] + len(c['name'])) for ch in '。\x1f') if k >= 0],
                      default=len(b['rest']))
            note = b['rest'][c['pos'] + len(c['name']):end].replace('\x1f', '')
            c['note'] = note
            c['tags'] = sorted({t for t, pat in TAGS if re.search(pat, note)})
            c.pop('pos')
        counties = got + extra
        out.append(dict(vol=b['vol'], name=b['jun'], cities=b['cities'],
                        households=b['hu'][0], population=b['hu'][1],
                        distanceFromLuoyang=b['dist'][0], direction=b['dist'][1],
                        anchor=b['anchor'],
                        seat=counties[0]['name'] if counties else None,  # 凡縣名先書者，郡所治也
                        resolved=len(got), candidate=len(extra),
                        checksum=('NO_COUNT' if b['cities'] is None else
                                  'PASS' if b['cities'] == len(counties) else 'FAIL'),
                        counties=counties))

    doc = dict(source='後漢書(續漢書) 郡國志 — 司馬彪 撰, public domain. 사본 출처 ctext.org',
               rule='凡縣名先書者，郡所治也',
               coordinates='CHGIS V6 (재배포 금지, ADR-LITE-039)',
               junCount=len(out), countyCount=sum(len(r['counties']) for r in out),
               places=out)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(doc, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1, sort_keys=True)

    res = sum(r['resolved'] for r in out); cand = sum(r['candidate'] for r in out)
    ok = sum(1 for r in out if r['checksum'] == 'PASS')
    nc = sum(1 for r in out if r['checksum'] == 'NO_COUNT')
    tag = Counter(t for r in out for c in r['counties'] for t in c['tags'])
    print(f'{OUT}: 郡國 {len(out)}개 / 縣 {doc["countyCount"]}개')
    print(f'  RESOLVED_POINT(CHGIS 좌표) {res} · CANDIDATE_REGION(원문 전용) {cand}')
    print(f'  城數 체크섬 PASS {ok}/{len(out)-nc}  (NO_COUNT {nc} — 屬國은 원래 城數 없음)')
    print(f'  지형 태그 {dict(tag.most_common())}')
    have = {r['name'] for r in out}
    miss = [j for j in CANON_105 if j not in have]
    print(f'  정경 105군국 대조: {len(CANON_105) - len(miss)}/105' + (f'  누락 {miss}' if miss else ' ✓'))


# 註 전용 글자만 남긴다. 城·國·山·門 은 실제 縣名(新城·安國·玉門)에 쓰이므로 넣지 않는다.
NOTE_CHARS = set('所此曰治故有今古本莽詩凡其右出')


def head_word(text, q):
    """縣 시작점 q 에서 註 시작 직전까지의 어절. 1~3자이고 註 어휘가 없어야 縣名으로 본다."""
    end = len(text)
    m = NOTE_START.search(text, q)
    if m:
        end = min(end, m.start())
    for ch in '。\x1f，、；：？！':
        k = text.find(ch, q)
        if 0 <= k < end:
            end = k
    w = text[q:end]
    if not (1 <= len(w) <= 3) or any(c in NOTE_CHARS for c in w):
        return None
    return w


def split_runs(text):
    """블록을 [縣名 연쇄] + [註] 로 쪼갠다.

    註는 한정된 어휘(NOTE_START)로 시작한다 — 실측으로 뽑은 네거티브 사전이다.
    註가 아닌 것은 전부 縣名이므로, CHGIS 사전에 없는 縣도 이 단계에서는 살아남는다.
    반환: [(name_run, note)] — note 는 그 run 의 마지막 縣에 붙는다.
    """
    out, n = [], len(text)
    bounds = [0] + [k + 1 for k, ch in enumerate(text) if ch in '。\x1f']
    for i, b in enumerate(bounds):
        if b >= n:
            break
        e = min([k for k in (text.find(c, b) for c in '。\x1f') if k >= 0], default=n)
        chunk = text[b:e]
        if not chunk:
            continue
        m = NOTE_START.search(chunk)
        if m and m.start() == 0:
            if out:                              # 앞 縣의 註가 이어진다
                out[-1] = (out[-1][0], out[-1][1] + chunk)
            continue
        cut = m.start() if m else len(chunk)
        for punct in '，、；：？！「」（）':
            k2 = chunk.find(punct)
            if 0 <= k2 < cut:
                cut = k2
        out.append((chunk[:cut], chunk[cut:]))
    return [(r, nt) for r, nt in out if r]


def best_split(run, lex, k):
    """run 을 정확히 k 조각(각 1~3자)으로 나누는 최적 분절. 없으면 None."""
    L = len(run)
    if not (k <= L <= 3 * k):
        return None
    NEG = float('-inf')
    dp = [[NEG] * (k + 1) for _ in range(L + 1)]
    back = [[None] * (k + 1) for _ in range(L + 1)]
    dp[0][0] = 0.0
    for i in range(L):
        for j in range(k):
            if dp[i][j] == NEG:
                continue
            for w in (1, 2, 3):
                if i + w > L:
                    break
                piece = run[i:i + w]
                # 사전 적중을 강하게 선호하되, CHGIS 결손(약 18%)을 감안해 미적중도 허용한다.
                sc = dp[i][j] + (2.0 if piece in lex else -1.0) + (0.3 if w == 2 else 0.0)
                if sc > dp[i + w][j + 1]:
                    dp[i + w][j + 1] = sc
                    back[i + w][j + 1] = w
    if dp[L][k] == NEG:
        return None
    pieces, i, j = [], L, k
    while j:
        w = back[i][j]
        pieces.append(run[i - w:i]); i -= w; j -= 1
    return dp[L][k], pieces[::-1]


def parse_counties(text, lex, cities):
    """城數를 하드 제약으로 걸고 縣을 분절한다. 城數가 없으면 자유 최적."""
    runs = split_runs(text)
    if not runs:
        return []
    # run 별로 조각 수 k 마다의 최적 점수표
    tables = []
    for run, _ in runs:
        col = {0: (0.0, [])}          # 이 run 을 통째로 註로 간주하는 선택지
        for k in range(1, min(len(run), 12) + 1):
            r = best_split(run, lex, k)
            if r:
                col[k] = r
        tables.append(col)

    if cities is None:
        chosen = [max(((k, v) for k, v in c.items() if k), key=lambda kv: kv[1][0] / kv[0])[0]
                  if len(c) > 1 else 0 for c in tables]
    else:
        # 조각 수 합 == 城數 인 조합 중 최대 점수 (배낭 DP)
        NEG = float('-inf')
        acc = {0: (0.0, [])}
        for col in tables:
            nxt = {}
            for total, (sc, path) in acc.items():
                for k, (s2, _) in col.items():
                    t2 = total + k
                    if t2 > cities:
                        continue
                    if nxt.get(t2, (NEG,))[0] < sc + s2:
                        nxt[t2] = (sc + s2, path + [k])
            acc = nxt or acc
        if cities in acc:
            chosen = acc[cities][1]
        elif acc:                                # 城數에 정확히 못 맞추면 가장 가까운 합
            best = min(acc, key=lambda t: (abs(t - cities), -acc[t][0]))
            chosen = acc[best][1]
        else:
            chosen = [1] * len(tables)

    counties = []
    for (run, note), col, k in zip(runs, tables, chosen):
        if k == 0:
            if counties:
                counties[-1]['note'] += run + note
            continue
        pieces = col.get(k, (0, [run]))[1]
        for idx, nm in enumerate(pieces):
            nt = note if idx == len(pieces) - 1 else ''
            counties.append(dict(name=nm, note=nt,
                                 inChgis=nm in lex,
                                 tags=sorted({t for t, pat in TAGS if re.search(pat, nt)})))
    return counties


if __name__ == '__main__':
    main()
