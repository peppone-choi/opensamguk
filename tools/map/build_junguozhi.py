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


# 「[CJK]{1,3}水」(水 로 끝나는 낱말 아무거나) 단독 항목은 뺐다 — 浿水·沁水처럼
# 진짜 縣名이 「水」로 끝나는 사례가 있어서, 이 항목이 그런 縣名 한가운데를 註
# 시작으로 오인해 그 앞 낱말 전체를 날려버렸다(樂浪郡 18→1 회귀). 강 발원지
# 註는 항상 「…水出」꼴이므로 그 패턴만 남긴다.
NOTE_START = re.compile(
    r'(有|又|或|一曰|今|古|故|本|秦|周|漢|春秋|世祖|光武|王莽|莽曰|詩|凡|其|右|'
    r'刺史治|都尉治|尉治|侯國|邑|[一-鿿]{1,3}水出|出)')
# run_end 전방탐색 전용: 1자 王朝명(秦·周·漢)은 뺐다 — 註 없는 縣들이 줄줄이
# 붙은 구간에서 이 글자가 진짜 縣名의 둘째 글자로 우연히 나오면(西河郡
# 「平周」의 「周」) run 이 한 글자 일찍 끊겨 「平」+「周」로 쪼개진다(西河郡
# 13→7 회귀 원인 중 하나). 이 자리는 「다음 縣이 어디서 시작하나」만 보면
# 되고 註 존재 자체를 확정할 필요는 없다 — 어차피 다음 「。」에서 멈춘다.
NOTE_START_RUN = re.compile(
    r'(有|又|或|一曰|今|古|故|本|春秋|世祖|光武|王莽|莽曰|詩|凡|其|右|'
    r'刺史治|都尉治|尉治|侯國|邑|[一-鿿]{1,3}水出|出)')

HEAD = re.compile(r'^[一-鿿]{1,7}(郡|國|尹|翊|風|屬國|都尉|校尉)$')
# 「侯國」「故國」처럼 註 안의 낱말이 헤더로 오인되지 않도록, 다음 세그먼트가
# 城數·戶口·雒陽거리로 시작하는지를 구조 검증으로 요구한다.
# 「候官」는 뺐다 — 실제 헤더 105개 중 이걸로만 확인되는 헤더가 하나도 없고,
# 上郡의 縣 「龜茲屬國」바로 뒤에 그 縣 자신의 부속 관서로 「候官」이 붙어 있어
# 이 낱말만으로는 진짜 郡 헤더와 縣 항목을 구분 못 한다(106번째 가짜 郡의 원인).
HEAD_NEXT = re.compile(r'([一二三四五六七八九十百]{1,4}城[，。]|戶[一二三四五六七八九十百]|口[一二三四五六七八九十百]|雒陽[東西南北]|刺史治|都尉治)')
# 註 안의 낱말이 헤더로 오인되는 것을 막는다 (「侯國」「故國」…).
NOT_HEAD = {'侯國', '故國', '本國', '父國', '國', '屬國', '都尉', '校尉', '故郡'}
# 郡國志는 州 하나가 끝날 때마다 郡 내용과 무관한 요약문을 덧붙인다
# (「右豫州刺史部，郡、國六，縣、邑、侯國九十九。」식, 12州 전부 확인). 이건
# 縣도 註도 아닌데 다음 郡 헤더 앞에서 끊기지 않아 그 州 마지막 郡의 마지막
# 縣 註로 통째로 삼켜진다 — 심하면 그 사이 낀 진짜 縣(日南郡 「比景」)까지
# 註 취급돼 사라진다. 블록 본문에 들어가기 전에 통째로 버린다.
STATE_TAIL = re.compile(r'^右[一-鿿]{1,3}州刺史部，.*。$')
# 「^」로 블록 맨 앞에만 고정하면 廣陽郡처럼 城數 앞에 연혁 註가 먼저 오는 郡
# (「世祖省并上谷，永平八年復。五城，…」)에서 註 안의 「，」가 CJK 밖 글자라 매치가
# 아예 안 되고, 그래서 5城 이 정말 있는데도 NO_COUNT 로 잘못 떨어진다. 앞 40자
# 안에서 처음 나오는 「…城，/。」패턴을 찾는 걸로 바꾼다 — 屬國처럼 城數가 정말
# 없는 곳은 이 패턴 자체가 없으니 영향 없다.
STATS = re.compile(r'([一-鿿]*)城[，。]')
HU = re.compile(r'戶([一-鿿]+?)，口([一-鿿]+?)。')
DIST = re.compile(r'雒陽(東北|東南|西北|西南|東|西|南|北)?([一-鿿]+?)里')

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


# checksum == NO_COUNT 가 정당한 郡國은 이 6개뿐이다 — 원문에 애초에 「몇十몇城」
# 선언이 없고 戶/口(또는 雒陽 거리)만 있다(屬國都尉 소속). 실측으로 확인했다
# (data/chgis-source/junguozhi/*.html 원문 셀 직접 대조). 새 NO_COUNT 가 이 목록
# 밖에서 나오면 그건 STATS 정규식이 城數를 못 찾은 새로운 파싱 결손이지 정당한
# 예외가 아니다 — 그래서 화이트리스트를 열어두지 않고 못박는다.
NO_COUNT_WHITELIST = frozenset((
    '廣漢屬國', '蜀郡屬國', '犍為屬國', '張掖屬國', '張掖居延屬國', '遼東屬國',
))

# split_unresolved_run 의 DP 는 註 없는 縣들이 줄줄이 붙은 구간에서 1자 縣名을
# 원칙적으로 안 믿는다(딴 지역 동명 1자가 진짜 2자 縣 경계를 가로챈다 — 西河郡
# 「平定」의 「平」). 그런데 진짜 1자 縣도 그런 구간 안에 낀다(西河郡 「藺」).
# 확증된 것만 명시적으로 허용한다 — 근거: (1) ctext 원문 셀 자체가 그 글자
# 하나만 담고 있다(data/chgis-source/junguozhi/wu.html, 「藺」셀), (2) CHGIS
# v6_time_cnty 에 漢代 존속 지점으로 등재돼 있다. 조용히 허용하지 않는다.
CONFIRMED_1CHAR_NAMES = frozenset((
    '藺',   # 西河郡. ctext 원문에서 앞뒤 縣과 분리된 단독 셀, CHGIS cnty_xy 확인.
))

# NOTE_START_RUN 은 註 없는 縣 나열 구간의 끝을 「有/其/凡...」 같은 註 시작어에서
# 끊는다. 그런데 그 글자가 진짜 縣名의 두 번째 글자로 우연히 낀 경우가 있다
# (樂浪郡 「屯有」의 「有」 — CHGIS 사전 밖 지명이라 PASS-A 사전매치가 못 잡고,
# run_end 탐색이 「有」에서 끊어 뒤 8개 縣을 통째로 註 취급해 삼켰다). 근거:
# 後漢書 卷113 郡國志 원문에 〖屯有〗로 명시. 확증된 것만 예외로 뚫는다.
CONFIRMED_NOTE_LIKE_NAMES = frozenset((
    '屯有',   # 樂浪郡. 後漢書 卷113 郡國志 원문 〖屯有〗.
))


def main():
    segs = read_segments()
    lex = county_lexicon()
    cnty_xy = chgis_points('cnty')
    pref_xy = chgis_points('pref')
    # 郡國志-CHGIS 이체자. 한 글자 차이로 같은 郡이 남남이 되고, 앵커를 못 찾은 郡은
    # 아래 필터가 통째로 꺼져 동명이인을 1500km 밖에서 물어온다.
    VARIANT = str.maketrans('鴈郁閒雒沇涼竝', '雁鬱間洛兗凉并')
    # CHGIS 판도 밖이라 앵커가 있을 수 없는 郡(交趾·九真·日南·樂浪·帶方…).
    # 좌표는 external-places.json 이 사료·Wikidata 로 비정한 것이며 필터 기준점으로만 쓴다.
    EXTRA_ANCHOR = {}
    try:
        for _e in json.load(open('data/map/external-places.json'))['places']:
            EXTRA_ANCHOR.setdefault(
                re.sub(r'(郡|國|尹)$', '', _e['nameCh']), []).append((_e['lon'], _e['lat']))
    except FileNotFoundError:
        pass  # 아직 안 만들어졌을 뿐 — 이 파일이 다루는 郡만 MAX_KM 필터가 꺼진다

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
    #    lookahead 는 다음 진짜 郡 헤더를 만나면 즉시 멈춘다. 안 멈추면 얕은 縣
    #    (예: 上郡 「龜茲屬國」)이 다음 郡의 城數 문구를 훔쳐 보고 자기가 헤더인
    #    척한다 — 龜茲屬國이 上郡에서 떨어져 나가 106번째 가짜 郡이 되던 원인.
    def head_confirmed(i):
        acc = ''
        for _, seg in merged[i + 1:i + 8]:
            if HEAD.match(seg) and seg not in NOT_HEAD:
                return False   # 다음 진짜 헤더를 먼저 만났다 — 후보 탈락
            acc += seg
            if HEAD_NEXT.search(acc[:140]):
                return True
            if len(acc) >= 140:
                return False
        return False

    blocks, cur = [], None
    for i, (vol, t) in enumerate(merged):
        # 헤더 뒤에 연혁 註가 먼저 오는 郡도 있다(廣陽郡→「世祖省并上谷…」) → 전방 탐색
        if HEAD.match(t) and t not in NOT_HEAD and head_confirmed(i):
            cur = dict(vol=vol, jun=t, body=[])
            blocks.append(cur)
        elif cur is not None and STATE_TAIL.match(t):
            # 州 요약문은 그 州의 마지막 郡 블록을 끝맺는 표시이기도 하다 — 특히
            # 맨 끝 交州/日南郡 뒤에는 다음 郡 헤더가 아예 없어서, 그냥 버리기만
            # 하면 블록이 안 닫히고 志 끝의 「漢書地理志…」 부기·「贊曰」까지
            # 전부 日南郡 縣 나열 구간으로 흘러든다. 블록을 여기서 닫는다.
            cur = None
        elif cur is not None:
            cur['body'].append(t)
    if not blocks:
        sys.exit('FATAL: 郡 블록 0건. ctext 사본이 깨졌다.')

    for b in blocks:
        # ctext 의 <td> 셀 경계는 縣/註 경계와 무관하다 — 숫자(十|城)뿐 아니라 縣名도
        # 셀 중간에서 잘린다(上郡 「雕」|「陰」). 셀을 그냥 이어 붙인다: 실제 경계는
        # 원문의 「。」뿐이다(독스트링 두 번째 규칙).
        b['text'] = ''.join(b['body'])
        m = STATS.search(b['text'][:40])
        b['cities'] = cn_int(m.group(1)) if m else None
        hu = HU.search(b['text'][:120])
        b['hu'] = (cn_int(hu.group(1)), cn_int(hu.group(2))) if hu else (None, None)
        di = DIST.search(b['text'][:120])
        b['dist'] = (cn_int(di.group(2)), di.group(1)) if di else (None, None)
        b['rest'] = b['text'][(hu.end() if hu else (m.end() if m else 0)):]
        key = re.sub(r'(郡|國|尹|屬國|都尉|校尉)$', '', b['jun'])
        # 이체자. 郡國志와 CHGIS 표기가 갈리면(鴈門/雁門, 郁林/鬱林, 河閒/河間) 앵커를
        # 못 찾고, 앵커가 없으면 아래 MAX_KM 필터가 통째로 꺼져 동명이인을 1500km
        # 밖에서 물어온다 — 交趾郡이 강소성에 찍히던 이유가 이것이다.
        # EXTRA_ANCHOR 를 pref_xy 보다 먼저 본다: 거기 실린 郡은 애초에 CHGIS
        # 판도 밖이라 pref_xy 에 없을 거라 가정하고 만든 화이트리스트인데, 日南郡
        # 처럼 CHGIS pref 레이어에 동명이인 점이 실제로는 있고(甘肅 방면, 실제
        # 日南 -Vietnam 에서 2000km 이상) 자기시대 검증도 없이 잡혀 앵커를
        # 통째로 오염시키는 경우가 있었다. EXTRA_ANCHOR 는 사료·Wikidata 로
        # 직접 비정한 값이라 더 신뢰된다.
        b['anchor_verified'] = key in EXTRA_ANCHOR
        b['anchor'] = (EXTRA_ANCHOR.get(key) or pref_xy.get(key)
                       or pref_xy.get(key.translate(VARIANT)) or [None])[0]
        b['assigned'] = []

    # 3) PASS A — CHGIS 縣名을 블록 본문에서 찾는다. 이름을 잘라내지 않으므로 날조가 없다.
    #    매칭은 縣 시작점(블록 처음 · 。 직후 · 앞 縣 직후)에서만 인정한다.
    #    註 안 우연 일치(有前亭 의 「前亭」)를 이 규칙이 걸러낸다.
    #
    #    한 세그먼트 안에 「알려진 縣 + CHGIS 결손 縣 + 알려진 縣」이 註 없이 연속할
    #    수 있다(예: 남궁扶柳...관진처럼 등재 안 된 縣이 사이에 낀 경우). 예전 코드는
    #    매칭이 한 번 끊기면 그 세그먼트를 통째로 포기하고 이름 후보 하나만 건졌다
    #    — 그래서 縣이 통째로 사라지거나(1번 증상) 뒤 縣들이 앞 縣 note 로 밀려
    #    들어갔다(2번 증상). 여기서는 실패해도 짧은 후보 하나만 소비하고 계속
    #    CHGIS 매칭을 재시도한다 — 註 시작 낱말(NOTE_START)을 만나야만 멈춘다.
    for b in blocks:
        rest = b['rest']
        # pref 앵커 자체가 틀릴 수 있다 — CHGIS pref 레이어가 해당 郡 이름에 대해
        # 後漢(25-220) 구간과 안 겹치는 딴 시대 점 하나만 갖고 있으면(예: 九江郡은
        # 王莽 시기 14-22년 점만 있고 그게 현대 江西 九江市 부근이라 원래 위치인
        # 安徽 壽春/陰陵 일대에서 400km 넘게 떨어져 있다), MAX_KM 필터가 원문에
        # 첫 번째로 나온 縣(독스트링 규칙상 郡治 그 자체)마저 걸러낸다. 郡治가
        # 걸러진다는 건 앵커가 못 믿을 지점이라는 뜻이므로, 그럴 때만 앵커를
        # 郡治 자신의 CHGIS 좌표로 바꿔 쓴다(날조가 아니라 같은 CHGIS 안에서 더
        # 신뢰되는 점으로 교체하는 것 — 九江郡 陰陵/壽春 소실의 원인이었다). 이
        # 보정은 아래 본 매칭 루프보다 먼저 해야 한다 — 본 루프의 縣名 인정 자체가
        # 앵커까지 참고하게 고칠 것이므로, 나중에 보정하면 이미 늦는다.
        # EXTRA_ANCHOR 로 이미 사료 검증된 앵커는 이 자기보정을 건너뛴다 — 여기
        # 씌우면 오히려 망가진다(日南郡: 진짜 縣 「西卷」의 「西」 한 글자가
        # cnty_xy 에 딴 지역 동명이인으로만 있어서, 검증된 앵커를 그 동명이인
        # 좌표로 갈아치워 버렸다).
        seat_guess = (None if b['anchor_verified'] else
                      next((rest[:L] for L in (4, 3, 2, 1) if rest[:L] in cnty_xy), None))
        if seat_guess and b['anchor']:
            seat_d = min(km(b['anchor'], q) for q in cnty_xy[seat_guess])
            if seat_d > MAX_KM:
                b['anchor'] = min(cnty_xy[seat_guess], key=lambda q: km(b['anchor'], q))

        # 縣名 인정에 앵커 거리를 같이 본다 — 이름만 보면 정경에 없는 딴 지역의
        # 우연한 동명 縣(定襄郡 「桐過武成駱」 안의 「成」은 CHGIS 상 山東 부근
        # 縣이다)을 진짜 縣으로 오인해, 그 縣 하나 앞뒤로 잘려야 할 「桐過」「武成」
        # 처럼 진짜 縣 두 개가 어긋나게 갈라진다.
        def near(nm):
            pts = cnty_xy.get(nm)
            if not pts:
                return False
            if b['anchor'] is None:
                return True
            return min(km(b['anchor'], q) for q in pts) <= MAX_KM

        entries = []
        n = len(rest)
        starts = [0] + [k + 1 for k, ch in enumerate(rest) if ch == '。']
        for s0 in starts:
            q = s0
            after_residual = False   # 註 없이 이어진 縣 목록 한복판인지 표시
            while q < n:
                # 1자 縣名(卷·鞏·京 등)은 흔해서 원칙적으로 인정한다. 다만 바로
                # 앞이 CHGIS 결손이라 DP 로 대충 떼어낸 잔여 조각(residual)이면
                # — 즉 註 없는 縣들이 구두점 없이 줄줄이 붙은 구간 한복판이면 —
                # 우연히 CHGIS에 있는 딴 지역 1자 縣(西河郡 「平定」의 「平」은
                # 실존하되 291km 밖 딴 縣이다)이 진짜 2자 縣의 앞글자를 가로채
                # 뒤가 통째로 어긋난다(西河郡 13→7 회귀 원인). 그 자리에서만
                # 1자 적중을 보류하고 run/DP 분절에 맡긴다.
                lens = (4, 3, 2) if after_residual else (4, 3, 2, 1)
                hit = next((rest[q:q+L] for L in lens
                            if rest[q:q+L] in cnty_xy and near(rest[q:q+L])), None)
                if hit:
                    entries.append([q, hit, True])
                    q += len(hit)
                    after_residual = False
                    continue
                if rest[q] == '。':
                    # 앞 縣을 CHGIS 매칭이 곧바로 삼켜, 그 縣 자신의 註 시작
                    # 「。」바로 위에 q 가 멈춰 서는 경우가 있다(河東郡 「安邑」
                    # 뒤 「。有鐵，有鹽池。」). 이 「。」 자체를 후보 縣名 조각으로
                    # 집어먹으면 縣名 자리에 구두점이 들어간다 — 건너뛴다.
                    q += 1
                    continue
                if NOTE_START.match(rest, q):
                    # 註 하나가 끝나기 전에 구두점 없이 진짜 縣이 더 이어지는
                    # 郡이 있다(巴郡 「涪陵出丹墊江安漢平都充國永元二年分閬中
                    # 置。」— 涪陵 註 「出丹」 뒤로 墊江·安漢·平都·充國 넉 縣이
                    # 註도 「。」도 없이 곧장 붙는다). 다음 「。」까지 무작정
                    # 다 註로 접으면 그 사이에 낀 진짜 縣이 통째로 없어진다.
                    # 다음 「。」 전까지 CHGIS 적중(근접 필터 포함)이 다시
                    # 나오는지 앞으로 훑어보고, 나오면 거기서부터 매칭을
                    # 재개한다 — 나오면 그 사이는 註, 안 나오면 그대로 註 확정.
                    p = q + 1
                    resync = None
                    while p < n and rest[p] != '。':
                        if next((True for L in (4, 3, 2)
                                 if rest[p:p+L] in cnty_xy and near(rest[p:p+L])), False):
                            resync = p
                            break
                        p += 1
                    if resync is None:
                        break    # 다음 「。」까지 진짜 縣이 더 없다 — 註로 확정
                    q = resync
                    after_residual = True
                    continue
                # 여러 縣이 註 없이 바로 붙어 있고(예: 西河郡 「離石平定美稷…」)
                # 그중 하나가 CHGIS 결손이면, 낱말 하나만 임의로 3자씩 끊어 가는
                # head_word 방식은 뒤 縣들까지 통째로 어긋나게 만든다. 다음 CHGIS
                # 적중 지점(혹은 註·「。」)까지의 구간을 통째로 떼어 best_split 의
                # 사전-선호 DP 로 나눈다.
                run_end = q + 1
                while run_end < n:
                    if rest[run_end] == '。':
                        break
                    if (NOTE_START_RUN.match(rest, run_end)
                            and rest[run_end-1:run_end+1] not in CONFIRMED_NOTE_LIKE_NAMES):
                        break
                    if any(rest[run_end:run_end+L] in cnty_xy and near(rest[run_end:run_end+L])
                           for L in (4, 3, 2, 1)):
                        break
                    run_end += 1
                run = rest[q:run_end]
                for piece in split_unresolved_run(run, lex, cnty_xy, b['anchor'], MAX_KM):
                    if piece:
                        entries.append([q, piece, False])
                        q += len(piece)
                q = run_end
                after_residual = True
        b['entries'] = entries
        # 같은 縣이 여러 번 나오면 첫 등장만 (郡治 판정이 첫 등장 순서에 달려 있다)
        first, order = {}, []
        for pos, nm, ok in sorted(entries):
            if ok and nm not in first:
                first[nm] = pos; order.append(nm)
        cand = []
        for nm in order:
            pts = cnty_xy[nm]
            d = min((km(b['anchor'], q) for q in pts), default=None) if b['anchor'] else None
            if d is not None and d > MAX_KM:
                continue
            # 동명이인은 앵커에 가장 가까운 것을 쓴다. 거리는 최근접으로 재놓고 좌표는
            # 첫 후보를 쓰면, 잰 것과 쓴 것이 다른 縣이 된다.
            q = min(pts, key=lambda q: km(b['anchor'], q)) if b['anchor'] else pts[0]
            cand.append(dict(name=nm, pos=first[nm], dist=d if d is not None else 9e9,
                             lon=q[0], lat=q[1], resolution='RESOLVED_POINT'))
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
            residual = [(pos, nm) for pos, nm, ok in b['entries'] if not ok]
            for pos, w in sorted(residual):
                if w in known or len(extra) >= gap:
                    continue
                known.add(w)
                extra.append(dict(name=w, pos=pos, tags=[], lon=None, lat=None,
                                  resolution='CANDIDATE_REGION'))
        counties = sorted(got + extra, key=lambda c: c['pos'])
        # note 는 이 縣이 끝난 지점부터 다음 「。」 또는 다음 縣의 시작점 중 먼저 오는
        # 곳까지다. 예전 코드는 다음 縣 시작점을 보지 않고 다음 「。」까지 무조건
        # 긁어서, 註 없는 縣들 사이에서는 뒤 縣들이 통째로 앞 縣의 note 로 흘러
        # 들어갔다(캐스케이드, 2번 증상) — 「남궁|扶柳下博武邑觀津」류.
        for idx, c in enumerate(counties):
            body_end = c['pos'] + len(c['name'])
            next_pos = counties[idx + 1]['pos'] if idx + 1 < len(counties) else len(b['rest'])
            punct = b['rest'].find('。', body_end)
            end = min(next_pos, punct) if punct >= 0 else next_pos
            end = max(end, body_end)
            note = b['rest'][body_end:end]
            c['note'] = note
            c['tags'] = sorted({t for t, pat in TAGS if re.search(pat, note)})
            c.pop('pos')
        out.append(dict(vol=b['vol'], name=b['jun'], cities=b['cities'],
                        households=b['hu'][0], population=b['hu'][1],
                        distanceFromLuoyang=b['dist'][0], direction=b['dist'][1],
                        anchor=b['anchor'],
                        seat=counties[0]['name'] if counties else None,  # 凡縣名先書者，郡所治也
                        resolved=len(got), candidate=len(extra),
                        checksum=('NO_COUNT' if b['cities'] is None else
                                  'PASS' if b['cities'] == len(counties) else 'FAIL'),
                        counties=counties))

    # 체크섬을 차단으로 승격한다 — 예전엔 checksum:「FAIL」을 써넣고도 정상
    # 종료해서, 縣을 놓친 걸 파서 스스로 알면서도 산출물을 그냥 내보냈다.
    # NO_COUNT 는 화이트리스트 밖이면 즉시 FAIL 과 동급으로 취급한다(원문에
    # 정말 城數가 없는 屬國 6개만 예외 — 위 NO_COUNT_WHITELIST 근거 참고).
    fails = [r['name'] for r in out if r['checksum'] == 'FAIL']
    bad_nc = [r['name'] for r in out
              if r['checksum'] == 'NO_COUNT' and r['name'] not in NO_COUNT_WHITELIST]
    if fails or bad_nc:
        if fails:
            print(f'FATAL: 城數 체크섬 FAIL {len(fails)}건 — cities != len(counties): {fails}',
                  file=sys.stderr)
        if bad_nc:
            print(f'FATAL: 화이트리스트에 없는 NO_COUNT {len(bad_nc)}건 '
                  f'(城數 파싱 결손일 수 있다): {bad_nc}', file=sys.stderr)
        sys.exit(1)

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
    """縣 시작점 q 에서 註 시작 직전까지의 어절. 1~3자이고 註 어휘가 없어야 縣名으로 본다.

    NOTE_START 탐색은 후보 낱말 구간(최대 3자) 안에서만 본다. 예전에는
    text 끝까지 검색해서, 뒤쪽 진짜 縣 안에 우연히 낀 註 낱말(예: 「平周」의
    「周」)이 註 시작으로 오인돼 그 앞 13자 전체가 통째로 버려졌다.
    """
    limit = min(len(text), q + 3)
    end = limit
    for k in range(q, limit):
        if NOTE_START.match(text, k):
            end = k
            break
    for ch in '。，、；：？！「」（）':
        k = text.find(ch, q, limit)
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


def best_split(run, hit, k):
    """run 을 정확히 k 조각(각 1~3자)으로 나누는 최적 분절. 없으면 None.

    hit(piece) 는 piece 를 조각으로 인정할지 판정하는 콜백이다(사전 적중 여부,
    필요하면 앵커 거리까지 포함).
    """
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
                sc = dp[i][j] + (2.0 if hit(piece) else -1.0) + (0.3 if w == 2 else 0.0)
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


def split_unresolved_run(run, lex, cnty_xy=None, anchor=None, max_km=None):
    """CHGIS 매칭이 안 되는(결손) 구간을 사전 선호 DP 로 분절한다.

    「離石平定美稷…」처럼 註 없는 縣들이 줄줄이 붙어 있는데 그중 일부가 CHGIS에
    없으면, 3자씩 맹목적으로 끊는 head_word 는 뒤 縣 이름까지 밀어서 어긋나게
    만든다(西河郡 13→5 회귀). best_split 은 이미 있던 DP인데 이전에는 아무 데서도
    안 불렸다 — 여기서 조각 수 k=1..len(run) 후보 중 조각당 평균 점수가 가장 높은
    분절을 고른다(cities 제약 없이 지역 최적).

    사전 적중은 이름만 보고 좌표를 안 본다 — 그래서 딴 지역의 우연한 1자 縣名
    (定襄郡 「桐過武成駱」 안의 「成」은 CHGIS 상 山東 부근 縣이다)이 DP 점수를
    거저 먹고 「桐過」「武成」을 갈라놓는다. anchor/cnty_xy 가 있으면 적중 판정에
    거리도 같이 본다 — 郡治에서 max_km 밖인 동명 縣은 적중으로 안 친다.
    """
    if not run:
        return []
    def hit(piece):
        # 이 run 자체가 이미 「처음부터 CHGIS 직접 매칭에 실패한」 구간이다
        # (2/3/4자는 물론 1자로도 안 걸렸으니까). 그런 자리에서 1자만 사전에
        # 우연히 있다고 점수를 얹으면, 딴 지역 동명 1자 縣(西河郡 「平定」의
        # 「平」)이 진짜 2자 縣 경계를 갈라버린다 — DP 는 그 우연한 +2.0 보너스를
        # 최적해로 착각한다. run 안에서는 1자 적중을 점수에 안 넣는다: 그래도
        # 2자 조각 보너스(+0.3)가 남아 있어 진짜 2자 縣 위주 분절은 그대로 유리하다.
        if piece in CONFIRMED_1CHAR_NAMES:
            return True
        if len(piece) == 1:
            return False
        if piece not in lex:
            return False
        if cnty_xy is None or anchor is None or max_km is None:
            return True
        pts = cnty_xy.get(piece)
        if not pts:
            return True   # lex 에는 있는데 cnty_xy 에 좌표가 없는 경우는 그냥 인정
        return min(km(anchor, q) for q in pts) <= max_km
    best = None
    for k in range(1, len(run) + 1):
        r = best_split(run, hit, k)
        if r is None:
            continue
        score, pieces = r
        avg = score / k
        if best is None or avg > best[0]:
            best = (avg, pieces)
    return best[1] if best else [run]


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
            r = best_split(run, lambda piece: piece in lex, k)
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
