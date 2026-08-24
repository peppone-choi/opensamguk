# -*- coding: utf-8 -*-
"""위키문헌 後漢書 郡國志 원문에서 「郡 → 縣名 목록」을 뽑는다.

이 모듈은 **파서 밖의 축**이다. build_junguozhi.py 의 산출물이 맞는지 보려면
파서가 안 쓰는 입력으로 같은 답을 따로 만들어 대조해야 한다 — 城數 체크섬처럼
파서 자신의 출력에서 나온 수를 세는 검사는 이 저장소에서 여덟 번 거짓 PASS 를
냈다(縣이 빠진 자리를 註 조각이 정확히 메워 개수가 맞아떨어진다).

그래서 이 파일은 다음을 **하지 않는다**. test_county_name_baseline.py 가 이
성질을 단언으로 못박는다 — 주석으로만 두면 다음 사람이 편의상 깨뜨린다:
  * build_junguozhi.py 를 import 하지 않는다
  * data/map/junguozhi.json 을 읽지 않는다

입력 (둘 다 ADR-LITE-039 로 gitignore — 없으면 호출부가 skip 한다):
  data/corpus/hhs-109..113.txt                        위키문헌 사본
  data/chgis-source/v6_time_{cnty,pref}_pts_utf_wgs84.dbf
      繁簡 글자표 생성용. 卷113 저본만 簡體라 對照에는 글자 변환이 필요한데,
      CHGIS 의 NAME_CH(簡)/NAME_FT(繁) 쌍은 지명 글자에 한해 정확하고 이것도
      파서 밖 데이터다. 일반 목적 변환기(opencc 등)는 이 환경에 없다.

한계 — 수치를 읽을 때 반드시 같이 읽어라:
  卷110/112 는 「縣名，註」 형식이라 여기서는 「　　」 문단 첫머리만 縣名으로
  본다. 그래서 **註 뒤에 문단 안에서 줄바꿈 없이 이어지는 縣을 못 뜬다**
  (실측 1건: 安平國 「經」 — 원문 「觀津，{註}經西有漳水…」). 즉 卷110/112 쪽
  縣 목록은 **과소 추정**이고, 그 卷의 「원문에 있는데 파서에 없는 縣」 수는
  실제보다 작게 나온다. 이 한계로 실제 파서가 맞았던 사례가 이미 있다.
"""
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / 'data/corpus'
CHGIS = ROOT / 'data/chgis-source'

# 卷109/111/113 은 縣名이 〖…〗로 묶여 있고, 卷110/112 는 「　　縣名，註」다.
VOLUMES = (('hhs-109', 'brace'), ('hhs-110', 'comma'), ('hhs-111', 'brace'),
           ('hhs-112', 'comma'), ('hhs-113', 'brace'))

_CITIES = re.compile(r'([一二三四五六七八九十百]+)城')
_SECTION = re.compile(r'^\s*={3,}\s*(.+?)\s*={3,}\s*$')
_HU = ('戶', '戸', '户')          # 저본마다 「호」자 글자꼴이 다르다
_TAIL = re.compile(r'^右.*刺史部')  # 「右豫州刺史部，郡、國六…」 — 州 합계줄, 縣 아니다


def _strip_templates(s):
    """{{*|…}} {{YL|…}} 를 중첩까지 걷어낸다. 註는 縣名이 아니다."""
    out, depth, i = [], 0, 0
    while i < len(s):
        if s.startswith('{{', i):
            depth += 1; i += 2; continue
        if s.startswith('}}', i) and depth:
            depth -= 1; i += 2; continue
        if not depth:
            out.append(s[i])
        i += 1
    return ''.join(out)


def _clean(line):
    line = _strip_templates(line)
    line = line.replace('-{', '').replace('}-', '')      # -{里}- 류 변환 억제 표기
    line = re.sub(r'\[\[[^\]]*\]\]', '', line)
    return re.sub(r"'{2,}", '', line)


def simp_to_trad():
    """CHGIS NAME_CH(簡) → NAME_FT(繁) 글자표. 길이가 같은 쌍만 글자별로 센다."""
    from collections import Counter, defaultdict
    votes = defaultdict(Counter)
    for layer in ('cnty', 'pref'):
        for simp, trad in _dbf_names(CHGIS / f'v6_time_{layer}_pts_utf_wgs84.dbf'):
            if simp and trad and len(simp) == len(trad):
                for a, b in zip(simp, trad):
                    votes[a][b] += 1
    return {a: c.most_common(1)[0][0] for a, c in votes.items()}


def _dbf_names(path):
    buf = path.read_bytes()
    nrec, hlen, rlen = struct.unpack('<IHH', buf[4:12])
    fields, off = [], 32
    while buf[off] != 0x0D:
        fields.append((buf[off:off+11].split(b'\0')[0].decode('ascii'), buf[off+16]))
        off += 32
    idx = {n: i for i, (n, _) in enumerate(fields)}
    for i in range(nrec):
        rec = buf[hlen + i*rlen: hlen + (i+1)*rlen]
        p, vals = 1, []
        for _, size in fields:
            vals.append(rec[p:p+size].decode('utf-8', 'replace').strip()); p += size
        yield vals[idx['NAME_CH']], vals[idx['NAME_FT']]


def _parse_volume(path, style):
    """한 卷을 [(郡 표제, [縣名…], 城數 원문)] 로 자른다."""
    out, counties, meta = [], None, None
    stop = False
    for raw in path.read_text(encoding='utf-8').splitlines():
        if re.match(r'^\s*==\s*校勘記', raw):
            stop = True          # 校勘記는 본문이 아니다 — 여기서 卷이 끝난다
        if stop:
            continue
        line = _clean(raw).strip()
        if not line:
            continue
        section = _SECTION.match(raw)
        if section:
            counties, meta = [], {'sec': section.group(1)}
            out.append((meta, counties))
            continue
        # 郡 머리글. 卷109~112 는 「N城，戶…」, 卷113 은 「◎　汉中郡秦置。…九城，…」
        # 로 郡名이 머리글 안에 있다(卷113 에는 == 절 표제가 없다).
        if line.startswith('◎') or (_CITIES.search(line) and '城，' in line
                                    and any(h in line for h in _HU)):
            if line.startswith('◎') or meta is None:
                name = re.match(r'^◎\s*(.+?)(?:秦置|武帝置|高帝置|明帝|安帝|故|置|，|。)', line)
                counties, meta = [], {'sec': name.group(1) if name else line[:8]}
                out.append((meta, counties))
            cities = _CITIES.search(line)
            if cities:
                meta['cities_raw'] = cities.group(1)
            continue
        if meta is None:
            continue
        if style == 'brace':
            counties.extend(x.strip() for x in re.findall(r'〖(.+?)〗', line))
        elif raw[:2] == '　　':
            # 「縣名，註」 — 첫 구두점까지가 縣名이다. 註 안에서 다시 시작하는
            # 縣은 여기서 안 잡힌다(모듈 docstring 의 한계 항목).
            head = re.match(r'^([^，。]+)[，。]?', line)
            if head:
                counties.append(head.group(1).strip())
    return out


def truth_counties():
    """[(郡 표제, [繁體 縣名…], 城數 원문, 卷, 저본 형식)] — 志 순서 그대로."""
    table = simp_to_trad()
    to_trad = lambda s: ''.join(table.get(c, c) for c in s)
    rows = []
    for vol, style in VOLUMES:
        for meta, counties in _parse_volume(CORPUS / f'{vol}.txt', style):
            if meta['sec'] == '校勘記':
                continue
            rows.append(dict(sec=to_trad(meta['sec']), vol=vol, style=style,
                             cities_raw=meta.get('cities_raw'),
                             counties=[to_trad(c) for c in counties if not _TAIL.match(c)]))
    return rows


if __name__ == '__main__':
    for r in truth_counties():
        print(f"{r['sec']:<14} {r['vol']}/{r['style']:<5} 城數={r['cities_raw']} "
              f"n={len(r['counties'])}  {' '.join(r['counties'])}")
