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
from collections import Counter, defaultdict

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
            # 十/百/千 처럼 「萬」도 앞자리 「一」이 생략된 채 바로 나올 수 있다
            # (酒泉郡 원문 「戶萬二千七百六」= 12706, 앞에 「一」 없이 「萬」이
            # 곧장 옴). `(section + n) or 1` 없이 그대로 곱하면 이 자리가 0 이
            # 돼 만 단위 전체가 사라진다(실측: 12706→2706, 上谷郡 10352→352).
            total += (section + n or 1) * 10000; section = n = 0
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
HU = re.compile(r'戶([一-鿿]+?)(?:，口([一-鿿]+?))?。')
# 酒泉郡처럼 口(인구) 없이 戶(호구)만 적힌 郡이 있다(data/chgis-source/junguozhi/
# si.html 「九城，戶萬二千七百六。福祿…」) — 口 절을 필수로 두면 이 郡은 통째로
# 안 걸려 「戶萬二千七百六。」가 그대로 縣 경계 탐색에 떨어져 가짜 縣으로 쪼개진다.
DIST = re.compile(r'雒陽(東北|東南|西北|西南|東|西|南|北)?([一-鿿]+?)里')

# 연호(永元‧永平‧建初‧陽嘉…) + 數字 + 「年」 + 행정동사 註는 縣名 앞뒤에 구두점도
# NOTE_START 트리거 글자도 없이 바로 붙기도 한다(上谷郡 「潘永元十一年復。」).
# 개별 연호 이름을 다 꼽는 화이트리스트 대신 구조로 잡는다 — 郡國志 卷109-113
# 縣名 중 「年」이 든 것은 CHGIS 직접매치로 이 검사 전에 이미 소비되는 廣年‧
# 萬年 둘뿐이다(cnty_xy 확인, 0918 감사).
# 연호명 앞은 정확히 2자로 못박는다(漢代 연호는 전부 2자) — 자릿수 제한 없이
# 「[CJK]{1,6}年復」처럼 느슨하게 두면 앞의 진짜 縣名(左馮翊 「祋祤」처럼 CHGIS
# 사전에 없어 잔여 조각으로 남아야 하는 이름)까지 연호명 자리로 통째로
# 삼켜버린다(실측: 「祋祤永元九年復」에서 「祋祤」가 통째로 사라졌다). 年 앞
# 숫자는 漢數字만 받는다 — 「元」(元年="첫 해")은 일부러 뺐다: run_end 스캔이
# 이 정규식을 여러 시작 위치에서 다 시도하다 보니, 「元」이 「…年」 앞
# 자릿수로도 「永元」 같은 연호명 둘째 글자로도 겹쳐 읽혀 「祋祤永元九年復」
# 에서 「祤永元九」를 통째로 (연호명+자릿수)로 오인해 「祋」한 글자만 남기고
# 「祤」까지 註로 삼켜버렸다(실측). 「元年」류 註 하나(延光元年復, 勃海郡)를
# 못 잡는 대가로 이 오탐을 막는다 — 개별 사고보다 일반 규칙의 안전이 먼저다.
REIGN_NOTE_START = re.compile(r'[一-鿿]{2}[一二三四五六七八九十百千]{0,3}年(復|置|省|并|罷|徙|屬|分|更)')

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

# 위와 같은 「확증된 1자 縣」이지만 CHGIS cnty_xy 에도 아예 없어(좌표 无) run 안
# 사전매치가 아니라 縣 경계 탐색 자체(PASS-A 주 스캔 루프)에서 인정해야 하는
# 자리. (郡, 縣名) 로 못박는다 — 근거: 後漢書 卷113 郡國志 원문 〖陇〗(漢陽郡
# 涼州刺史部 소재지 縣, data/corpus/hhs-113.txt). ctext 원문 셀은 「隴」 단독,
# 바로 뒤 셀이 註(「州刺史治。有大阪名隴坻。豲坻聚有秦亭。…」)다. CHGIS 사전에
# 「隴」이 없어 q=0 에서 PASS-A 직접매치가 실패, 뒤 註의 「州」까지 한 run 으로
# 삼켜 「隴州」라는 가짜 縣名이 되고 그 여파로 뒤 顯親‧上邽 두 縣이 PASS-B
# gap 예산(11칸)을 가짜 잔여 조각(隴州‧豲‧坻聚 3개)에 뺏겨 통째로 탈락했다.
CONFIRMED_1CHAR_JUN_NAMES = frozenset((
    ('漢陽郡', '隴'),
    # 上谷郡 「甯」. 後漢書 卷113 郡國志 원문 〖甯〗(data/corpus/hhs-113.txt:770
    # 부근, 상현 「〖潘〗{{YL|永元十一年}}复。〖甯〗」). CHGIS cnty_xy·county_
    # lexicon() 둘 다에 「甯」이 없다(직접 조회 확인) — q=0 PASS-A 직접매치가
    # 실패해 앞 縣 「潘」의 註(「永元十一年復」) 뒤에 그대로 이어붙어 있다가
    # run_end/DP 로 떨어지면 뒤 「廣甯」과 한 run 으로 묶여 「甯廣」「甯居」「庸」
    # 세 가짜 縣으로 쪼개진다(실측).
    ('上谷郡', '甯'),
))

# 위와 같은 문제이지만 縣名이 2자 이상인 자리. CHGIS cnty_xy 에 없으면 q=0
# PASS-A 직접매치·NOTE_START 판정 둘 다 못 미치고 곧장 run_end/DP 로 떨어져,
# 縣名 자신이 사전(lex) DP 분절 대상이 돼 뒤 註까지 통째로 오분절된다(下記
# 右扶風 「槐里」 — 자신은 못 잡히고, 그 결과 뒤 「周曰犬丘，高帝改」 註가
# NOTE_START 재동기화 진입점을 잃어 DP 가 「周曰」「犬丘」「，高」「帝改」 네
# 가짜 縣으로 쪼갠다). (郡, 縣名) 로 못박아 CONFIRMED_1CHAR_JUN_NAMES 와
# 동일하게 좌표 없는 candidate 조각으로 자르고, 그 직후 위치가 확증된 註
# 연속(CONFIRMED_NOTE_CONTINUATION)이면 縣 경계 시도를 그대로 그친다.
CONFIRMED_JUN_NAMES_NO_CHGIS = frozenset((
    # 右扶風 「槐里」— 郡治(治所) 縣. 後漢書 卷109 郡國一 원문 〖槐里〗周曰犬丘，
    # 高帝改。(data/corpus/hhs-109.txt:224). CHGIS cnty_xy 에 「槐里」좌표가 없다
    # (오늘날 陝西 興平 일대 — CHGIS V6 縣급 레이어 결손, pref_xy 郡治 좌표만
    # 있을 수 있음). 좌표는 못 채우고 CANDIDATE_REGION 으로 남긴다.
    ('右扶風', '槐里'),
    # 上谷郡 「廣甯」・「居庸」. 後漢書 卷113 郡國志 원문 〖广甯〗〖居庸〗
    # (data/corpus/hhs-113.txt:770 부근, 「甯」 다음·다다음 縣). CHGIS cnty_xy·
    # county_lexicon() 둘 다에 없다(직접 조회 확인). 「甯」 확정 후에도 이 두
    # 縣이 사전에 없어 뒤이은 「雊瞀」까지 한 run 으로 묶이면 다시 오분절되므로
    # 「甯」과 함께 명시적으로 못박는다.
    ('上谷郡', '廣甯'),
    ('上谷郡', '居庸'),
))

# NOTE_START_RUN 은 註 없는 縣 나열 구간의 끝을 「有/其/凡...」 같은 註 시작어에서
# 끊는다. 그런데 그 글자가 진짜 縣名의 두 번째 글자로 우연히 낀 경우가 있다
# (樂浪郡 「屯有」의 「有」 — CHGIS 사전 밖 지명이라 PASS-A 사전매치가 못 잡고,
# run_end 탐색이 「有」에서 끊어 뒤 8개 縣을 통째로 註 취급해 삼켰다). 근거:
# 後漢書 卷113 郡國志 원문에 〖屯有〗로 명시. 확증된 것만 예외로 뚫는다.
CONFIRMED_NOTE_LIKE_NAMES = frozenset((
    '屯有',   # 樂浪郡. 後漢書 卷113 郡國志 원문 〖屯有〗.
))

# 縣 하나의 註가 「。」로 끝나는 절 여러 개로 이어지는 경우가 있다(雒陽 註
# 「有唐聚。有士鄉聚。…」류). 그런 절의 첫머리가 CHGIS 상 딴 지역의 동명 縣과
# 우연히 겹치면, 도성 河南尹처럼 CHGIS 점이 밀집한 郡에서는 400km 근접 필터도
# 못 걸러 縣 경계로 오인된다 — 「河南尹」「河南」縣 註 「東城門名鼎門，北城門名
# 乾祭。」(성문 東·北 이름 서술, 後漢書 卷109 郡國一 원문 확인)의 「東城」이
# 그래서 縣으로 오인돼 뒤 진짜 縣 梁‧滎陽‧菀陵‧成皋‧匽師가 통째로 註로
# 삼켜졌다. 확증된 절만 (郡, 절 첫머리) 로 縣 경계 시도 자체를 건너뛴다 — 뒤에
# entry 가 안 생기면 note 조립 단계가 알아서 앞 縣(河南)의 註로 이어붙인다.
CONFIRMED_NOTE_CONTINUATION = (
    ('河南尹', '東城門名鼎門，'),
    # 漢陽郡 「隴」縣 자신의 註 첫머리. ctext 원문 셀이 「州刺史治。…」로 시작하는데
    # 앞의 「州」는 앞 셀(縣名 「隴」)과도, 이 註의 나머지(「刺史治」)와도 CHGIS/사전
    # 매치가 없어 그대로 두면 홀로 잔여 조각("州")이 돼 gap 예산을 하나 더 먹는다.
    # 後漢書 卷113 원문(〖陇〗刺史治。…)엔 「州」가 없다 — ctext 필사 이문으로 보고
    # CONFIRMED_1CHAR_JUN_NAMES 로 「隴」을 먼저 자른 뒤, 이 절 전체를 註로 확정한다.
    ('漢陽郡', '州刺史治'),
    # 「隴」註의 나머지 두 절. 「有」로 시작해 NOTE_START 는 잡지만, 절 안의
    # 「豲」「坻聚」가 CHGIS/사전 어디에도 없어 run/DP 가 이걸 縣 조각인 양
    # 1+2자로 쪼갠다(後漢書 卷113 원문 확인 — 豲坻聚 는 지명 서술이지 縣이 아니다.
    # 진짜 「豲道」 縣은 뒤 「豲道蘭干平襄…」절에 따로, 온전한 이름으로 나온다).
    ('漢陽郡', '有大阪名隴坻'),
    ('漢陽郡', '豲坻聚有秦亭'),
    # 右扶風 「武功」 註의 세 번째 절(卷109 원문: 〖武功〗永平八年復。有太一山，
    # 本終南。垂山，本敦物。有斜谷。data/corpus/hhs-109.txt:229). 「有太一山，
    # 本終南。」「有斜谷。」 두 절은 「有」로 시작해 NOTE_START 재동기화가 절로
    # 확정하지만(太一/終南/斜谷 모두 CHGIS 밖, 직접 확인), 「垂山，本敦物。」만은
    # 「垂」로 시작해 NOTE_START 키워드가 아니라 곧장 run_end/DP 로 떨어진다.
    # 「垂」가 사전(lex) 안 딴 지역 실존 지명(垂水 등)과 우연히 겹쳐 가짜 縣
    # 「垂」(註 「山，本敦物」)를 냈고, 원래 槐里 절 註 파편(周曰/犬丘/，高/帝改)
    # 4개가 채우던 PASS-B gap 예산이 그 파편 수정으로 비면서 뒤로 밀렸던 이
    # 잔여 조각이 새로 gap 을 채워 들어왔다(right扶風 15城 상한이라 못 보이던
    # 것이 드러남 — CANDIDATE_REGION 증가가 아니라 가짜 縣 자리바꿈이라 별도 봉쇄).
    ('右扶風', '垂山，本敦物'),
    # 右扶風 「武功」 註 첫 절(卷109 원문 위와 동일: 〖武功〗永平八年復。…). run_end
    # 스캔은 이 run 을 통째로 「永平八年復」로 떼어 split_unresolved_run 의 DP 로
    # 넘기는데, 「八年」「復」은 cnty_xy/lex 에 근접매치가 없어 DP 가 정보 없는
    # run 을 2자씩 그리디로 짝짓는다(槐里 절의 周曰/犬丘/，高/帝改 와 같은
    # fallback 분절) — 「永平」이 cnty_xy 에 딴 지역(江蘇 방면, >1000km) 좌표로
    # 실존하지만 이 경로는 근접 필터를 타지 않는 lex 기반 DP 라 걸러지지 않는다.
    # 「垂山，本敦物」 봉쇄로 gap 예산이 다시 비면서 드러났다 — PASS-A 정상
    # hit(武功) 직후 위치라 s0 top 체크가 아니라 hit 분기 안
    # CONFIRMED_NOTE_CONTINUATION 재확인이 잡는다.
    ('右扶風', '永平八年復'),
)

# 註가 다른 縣을 방향·경로 참조로만 언급하는 사고는 예전엔 (郡, 절 첫머리)
# 화이트리스트로 하나씩 막았다(河南尹 「有長城，經陽武」‧「有前亭」‧「有高都」,
# 南陽郡 「有小長安」 — 넷 다 「有」로 시작하는 절이었다). 이제는 아래 NOTE_START
# 재동기화 분기에서 「有」로 시작하는 절 자체를 재동기화 대상에서 빼는 일반
# 규칙으로 대체됐다 — CONFIRMED_NOTE_INTERNAL_REFERENCE 는 삭제, 그 자리의
# 주석에 근거를 남겼다.


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
            if any(jun == b['jun'] and rest[s0:].startswith(prefix)
                   for jun, prefix in CONFIRMED_NOTE_CONTINUATION):
                continue    # 앞 縣 註의 다음 절 — 縣 경계 시도를 건너뛴다
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
                    if any(jun == b['jun'] and rest[q:].startswith(prefix)
                           for jun, prefix in CONFIRMED_NOTE_CONTINUATION):
                        break   # 바로 뒤가 확증된 註 연속 — 縣 경계 시도를 그친다
                    continue
                # 위와 같은 문제의 2자 이상 縣名 판(右扶風 「槐里」류) — cnty_xy 에
                # 없으니 위 hit 체크로는 못 잡는다. 먼저 시도해 자신을 잘라내야
                # 뒤 註가 NOTE_START 재동기화 진입점을 되찾는다.
                jun_hit = next((nm for j, nm in CONFIRMED_JUN_NAMES_NO_CHGIS
                                if j == b['jun'] and rest[q:q+len(nm)] == nm), None)
                if jun_hit:
                    entries.append([q, jun_hit, False])
                    q += len(jun_hit)
                    if any(jun == b['jun'] and rest[q:].startswith(prefix)
                           for jun, prefix in CONFIRMED_NOTE_CONTINUATION):
                        break   # 바로 뒤가 확증된 註 연속 — 縣 경계 시도를 그친다
                    after_residual = True
                    continue
                # CHGIS 사전에 아예 없는 확증된 1자 縣(漢陽郡 「隴」류) — cnty_xy 에
                # 없으니 위 hit 체크로는 못 잡는다. 좌표 없는 잔여 조각으로 잘라
                # PASS-B 로 넘긴다(ok=False, 藺 과 같은 취급). 안 자르면 뒤 註 글자
                # (「州」)까지 한 run 으로 삼켜 縣名 자리에 註가 섞인다.
                if (b['jun'], rest[q]) in CONFIRMED_1CHAR_JUN_NAMES:
                    entries.append([q, rest[q], False])
                    q += 1
                    if any(jun == b['jun'] and rest[q:].startswith(prefix)
                           for jun, prefix in CONFIRMED_NOTE_CONTINUATION):
                        break   # 바로 뒤가 확증된 註 연속 — 縣 경계 시도를 그친다
                    after_residual = True
                    continue
                if rest[q] == '。':
                    # 앞 縣을 CHGIS 매칭이 곧바로 삼켜, 그 縣 자신의 註 시작
                    # 구두점 바로 위에 q 가 멈춰 서는 경우가 있다(河東郡 「安邑」
                    # 뒤 「。有鐵，有鹽池。」). 이 구두점 자체를 후보 縣名 조각으로
                    # 집어먹으면 縣名 자리에 구두점이 들어간다 — 건너뛴다.
                    # 「，」는 여기서 같이 다루지 않는다 — 시도했다가 되돌렸다
                    # (汝南郡 「宋公國，周名郪丘，漢改為新郪，章帝建初四年徙宋公
                    # 於此。」처럼 註 안 산문 쉼표가 節 경계로 잘못 읽혀 진짜 縣
                    # 北宜春 을 통째로 삼키고 가짜 「章」을 냈다). 下邳國「山，」류는
                    # run_end 쪽만 좁게 고친다 — 아래 주석 참고.
                    q += 1
                    continue
                if NOTE_START.match(rest, q) or REIGN_NOTE_START.match(rest, q):
                    # 註 하나가 끝나기 전에 구두점 없이 진짜 縣이 더 이어지는
                    # 郡이 있다(巴郡 「涪陵出丹墊江安漢平都充國永元二年分閬中
                    # 置。」— 涪陵 註 「出丹」 뒤로 墊江·安漢·平都·充國 넉 縣이
                    # 註도 「。」도 없이 곧장 붙는다). 다음 「。」까지 무작정
                    # 다 註로 접으면 그 사이에 낀 진짜 縣이 통째로 없어진다.
                    # 다음 「。」 전까지 CHGIS 적중(근접 필터 포함)이 다시
                    # 나오는지 앞으로 훑어보고, 나오면 거기서부터 매칭을
                    # 재개한다 — 나오면 그 사이는 註, 안 나오면 그대로 註 확정.
                    # 그런데 이 재동기화가 註 안의 낱말(有前亭。의 「前亭」, 「經
                    # 陽武到密。」의 「陽武」처럼 CHGIS 우연 일치·註 안 방향 참조)
                    # 까지 縣으로 삼키는 사고가 실측됐다(河南尹: 雒陽 註 「有前亭。」
                    # 의 「前亭」이 縣으로 오인돼 뒤 5개 진짜 縣 梁‧滎陽‧菀陵‧成皋‧
                    # 匽師가 통째로 삼켜졌다). 「빈 tail 이면 거부」·「연달아 적중
                    # 해야 인정」 둘 다 전역 규칙으로 시도했지만, 涪陵류 진짜
                    # 재동기화까지 걷어차거나 蘇子‧高帝처럼 註 낱말을 縣으로 오인
                    # 하는 반대 방향 오탐을 다른 郡 다수에서 냈다 — 둘 다 되돌렸다.
                    # 사료로 확증된 자리만 (郡, q부터 적중까지 원문 그대로) 로 개별
                    # 봉쇄한다 — 다른 郡의 진짜 재동기화는 그대로 둔다.
                    # 「有」로 시작하는 절은 이 재동기화 대상에서 통째로 뺀다.
                    # 「有」는 문법상 "…이 있다"로 항상 절 안 서술(有X亭‧有X城‧
                    # 有X關‧有X山‧有X聚‧有X鄉‧有X 등 지물·별칭)을 여는 글자이지
                    # 縣名 첫 글자로 실존한 예가 郡國志 卷109-113 원문 전체에
                    # 단 한 건도 없다(data/corpus/hhs-109~113.txt 전량을
                    # 〖有…〗 패턴으로 재검색 — 0건. 樂浪郡 「屯有」는 반대로 有가
                    # 縣名의 *둘째* 글자라 이 조건과 안 겹친다, CONFIRMED_NOTE_
                    # LIKE_NAMES 로 별도 처리). 개별 (郡, 절) 화이트리스트로
                    # 하나씩 막았던 河南尹 「有前亭」‧「有高都」‧「有長城，經
                    # 陽武」, 南陽郡 「有小長安」 넷이 전부 이 패턴이었다 — 사료
                    # 조사(.ai/research/2026-08-24-namu-places-crosscheck.md
                    # §C-6-2 (다))도 陳留郡 繁陽(외黃 「有繁陽城」)‧汝南郡 繁陽
                    # (宋 「有繁陽亭」)‧東平國 堂陽(須昌 「有堂陽亭」)‧東郡 平陽
                    # (燕 「有平陽亭」)‧泰山郡 東陽(南城 「有東陽城」)‧敦煌郡
                    # 玉門(龍勒 「有玉門關」) 을 같은 부류로 실측했다 — 개별
                    # 사고가 아니라 한 클래스다. 일반 규칙으로 대체한다.
                    if rest[q] == '有':
                        resync = None
                    else:
                        p = q + 1
                        resync = None
                        while p < n and rest[p] != '。':
                            hit_len = next((L for L in (4, 3, 2)
                                            if rest[p:p+L] in cnty_xy and near(rest[p:p+L])), None)
                            if hit_len:
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
                    if REIGN_NOTE_START.match(rest, run_end):
                        # 연호 註도 run 경계로 인정한다 — 안 그러면 run 이 이 글자를
                        # 넘어 註까지 통째로 삼켜 DP 가 「祋祤」+「永元」류로 쪼갠다
                        # (左馮翊 「祋祤永元九年復」실측).
                        break
                    if any(rest[run_end:run_end+L] in cnty_xy and near(rest[run_end:run_end+L])
                           for L in (4, 3, 2, 1)):
                        break
                    if (b['jun'], rest[run_end]) in CONFIRMED_1CHAR_JUN_NAMES:
                        # 확증된 1자 縣(CHGIS 사전 밖)도 run 경계로 인정한다 — 안
                        # 그러면 DP 가 이 글자를 뒤 註 글자와 짝지어(「隴」+「州」
                        # → 「隴州」) 縣名 자리에 註를 섞는다.
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

    # 3.5) 郡을 건너뛴 좌표 중복 정리 — 위 앵커 최근접 선택은 郡 하나 안에서
    # 縣名이 겹칠 때만 본다. 다른 郡 두 곳이 CHGIS 상 같은 縣名에 딴 후보점이
    # 여럿 있는데도(예: 「信都」4점, 「安平」9점) 우연히 같은 점을 각자
    # 최근접으로 골라, 진짜 縣과 註 안 지명(「故屬信都」의 「信都」류, 아직
    # 파싱 규칙으로 못 걸러낸 잔여 결손)이 좌표를 나눠 갖는 경우가 있다
    # (105군국 좌표중복 감사 34그룹/78현). 縣名 자체는 여기서 안 건드린다 —
    # 겹친 점 중 郡 앵커에 더 가까운 쪽만 좌표를 유지하고, 나머지는 좌표를
    # 뗀다(가짜 좌표를 지어내는 것보다 CANDIDATE_REGION 으로 남기는 게
    # 낫다 — spec 2026-07-13 :291). 좌표를 뗀 자리는 PASS B 의 gap 메우기가
    # 그대로 이어받는다.
    #
    # 좌표를 뗀 항목을 assigned 에서 통째로 지우면, 그 縣이 城數 집계에서도
    # 사라져 len(counties) 가 城數보다 모자라 체크섬 FAIL 로 번진다(잔여
    # 결손 목록 residual 은 ok=False 항목만 채우므로 이 ok=True 항목을 다시
    # 못 줍는다). 지우지 않고 demoted 로 표시만 해서 PASS B 가 CANDIDATE_REGION
    # 으로 그대로 세도록 한다.
    by_point = defaultdict(list)
    for b in blocks:
        for c in b['assigned']:
            by_point[(round(c['lat'], 6), round(c['lon'], 6))].append((b, c))
    for pt, owners in by_point.items():
        if len(owners) < 2:
            continue
        owners.sort(key=lambda bc: (bc[1]['dist'], bc[0]['jun'], bc[1]['name']))
        for b, c in owners[1:]:
            c['demoted'] = True

    # 4) PASS B — 城數에 모자란 몫은 원문에서만 존재하는 縣이다(CHGIS 결손).
    #    좌표를 지어내지 않고 CANDIDATE_REGION 으로 남긴다 (spec 2026-07-13 :291).
    out, gap_total = [], 0
    for b in blocks:
        got = sorted((c for c in b['assigned'] if not c.get('demoted')), key=lambda c: c['pos'])
        demoted = sorted((c for c in b['assigned'] if c.get('demoted')), key=lambda c: c['pos'])
        for c in got:
            c.pop('dist', None)
        extra = []
        for c in demoted:
            c.pop('dist', None)
            c.pop('demoted', None)
            c['lon'] = None
            c['lat'] = None
            c['resolution'] = 'CANDIDATE_REGION'
            extra.append(c)
        gap = (b['cities'] - len(got) - len(extra)) if b['cities'] else 0
        if gap > 0:
            # extra 는 이미 demoted 항목을 담고 시작할 수 있다 — len(extra) 를
            # gap 과 그대로 비교하면 demoted 로 미리 채워진 몫만큼 덜 채우게
            # 된다. 여기서 새로 보태는 개수만 따로 센다.
            added = 0
            known = {c['name'] for c in got} | {c['name'] for c in extra}
            residual = [(pos, nm) for pos, nm, ok in b['entries'] if not ok]
            for pos, w in sorted(residual):
                if w in known or added >= gap:
                    continue
                added += 1
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
