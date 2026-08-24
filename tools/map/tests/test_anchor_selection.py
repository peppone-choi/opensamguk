# -*- coding: utf-8 -*-
"""郡 앵커가 **後漢 시대의 점**인지 본다.

앵커는 縣 인정을 MAX_KM 로 거르는 기준점이다. chgis_points 의 시대 창은
-206~280 이라 前漢·王莽·三國 점이 다 들어오고, 이름별 첫 점이 dbf 파일
순서로 정해지면 딴 시대 점이 앵커가 된다 — pref 앵커 258개 중 14개(105 郡
중 9개)가 그렇게 정해지고 있었고 犍為는 407km, 零陵은 96km 어긋났다.

산출물 diff 는 0 이다(실측). 그래서 이 성질은 **산출물로는 안 보인다** —
검사로 못박지 않으면 조용히 되돌아간다.

data/chgis-source/** 는 ADR-LITE-039 로 gitignore 라 CI 에서는 skip 된다.
"""
import importlib.util
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DBF = ROOT / 'data/chgis-source/v6_time_pref_pts_utf_wgs84.dbf'
OUT = ROOT / 'data/map/junguozhi.json'

# 後漢(25~220)에 존속했고 CHGIS pref 레이어에 딴 시대 동명점이 같이 있는 郡.
# 시대 선호가 없으면 이 이름들의 첫 점이 前漢/三國 점으로 잡힌다(실측).
ERA_CONTESTED = ('犍為', '零陵', '北地', '琅邪', '濟北', '上黨', '東平', '廣漢', '陳留')


def _build():
    spec = importlib.util.spec_from_file_location(
        'build_junguozhi', ROOT / 'tools/map/build_junguozhi.py')
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


def _max_km():
    """MAX_KM 은 main() 안의 지역변수라 import 로는 못 읽는다 — 소스에서 읽는다.

    검사가 자기 임계값을 새로 만들면 파서가 실제로 쓰는 값과 갈라진다.
    """
    src = (ROOT / 'tools/map/build_junguozhi.py').read_text(encoding='utf-8')
    m = re.search(r'^\s*MAX_KM\s*=\s*([\d.]+)', src, re.M)
    assert m, 'MAX_KM 을 소스에서 못 찾았다 — 이 검사의 전제가 깨졌다.'
    return float(m.group(1))


def _later_han_points(path):
    """dbf 를 직접 읽어 「이름 → 後漢(25~220) 존속 점 좌표 집합」을 만든다.

    build_junguozhi 의 chgis_points 는 연대를 버리고 좌표만 돌려준다 — 그
    출력만 보면 첫 점이 後漢 점인지 알 수 없다. 그래서 검사는 연대를 저본에서
    **따로** 읽는다. 같은 함수를 불러 비교하면 검사가 버그를 공유한다.
    """
    import struct
    buf = path.read_bytes()
    nrec, hlen, rlen = struct.unpack('<IHH', buf[4:12])
    fields, off = [], 32
    while buf[off] != 0x0D:
        fields.append((buf[off:off + 11].split(b'\0')[0].decode('ascii'), buf[off + 16]))
        off += 32
    idx = {n: i for i, (n, _) in enumerate(fields)}
    out = {}
    for i in range(nrec):
        rec = buf[hlen + i * rlen: hlen + (i + 1) * rlen]
        if rec[:1] == b'*':
            continue
        p, vals = 1, []
        for _, size in fields:
            vals.append(rec[p:p + size].decode('utf-8', 'replace').strip()); p += size
        try:
            beg, end = int(float(vals[idx['BEG_YR']])), int(float(vals[idx['END_YR']]))
            xy = (float(vals[idx['X_COOR']]), float(vals[idx['Y_COOR']]))
        except ValueError:
            continue
        if not (beg <= 220 and end >= 25):
            continue
        nm = vals[idx['NAME_FT']] or vals[idx['NAME_CH']]
        for suf in ('縣', '县', '侯國', '侯国', '郡', '國', '国', '尹', '道', '邑'):
            if nm.endswith(suf) and len(nm) > len(suf):
                nm = nm[:-len(suf)]; break
        out.setdefault(nm, set()).add(xy)
    return out


@unittest.skipUnless(DBF.exists(),
                     'data/chgis-source/** 는 ADR-LITE-039 로 gitignore 다 — CI 에서 skip.')
class TestAnchorEra(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import os
        os.chdir(ROOT)                       # build_junguozhi 는 상대경로로 읽는다
        cls.pref = _build().chgis_points('pref')

    def test_contested_names_anchor_on_a_later_han_point(self):
        """첫 점 = 앵커다. 後漢 점이 있는데도 딴 시대 점이 앞에 오면 FAIL."""
        han = _later_han_points(DBF)
        for name in ERA_CONTESTED:
            with self.subTest(name=name):
                self.assertIn(name, self.pref, f'{name} 이 pref 레이어에서 사라졌다')
                self.assertIn(
                    name, han,
                    f'{name} 에 後漢 점이 하나도 없다 — 이 표본은 더 이상 시대 '
                    f'경합이 아니다. 목록을 갱신하든지, 왜 사라졌는지 먼저 밝혀라.')
                self.assertGreater(
                    len(self.pref[name]), len(han[name]),
                    f'{name} 에 딴 시대 동명점이 없다 — 경합 표본이 아니다.')
                self.assertIn(
                    self.pref[name][0], han[name],
                    f'{name} 앵커가 後漢 점이 아니다. 딴 시대 점을 기준으로 '
                    f'MAX_KM 를 재면 진짜 縣이 걸러진다(犍為 407km, 零陵 96km).')

    def test_anchor_point_is_the_first_element(self):
        """앵커가 [0] 을 쓰는 한 이 순서가 곧 앵커다 — 그 계약을 소스에서 확인한다."""
        src = (ROOT / 'tools/map/build_junguozhi.py').read_text(encoding='utf-8')
        self.assertIn("or pref_xy.get(key.translate(VARIANT)) or [None])[0]", src,
                      '앵커가 더 이상 pref 목록의 첫 원소가 아니다 — 이 파일의 '
                      '전제가 깨졌으니 검사를 다시 세워라.')


@unittest.skipUnless(OUT.exists(),
                     'data/map/junguozhi.json 은 gitignore 다 — CI 에서 skip.')
class TestSeatCorrectionDirection(unittest.TestCase):
    """자기보정(seatfix)이 옳은 앵커를 딴 지역으로 갈아치우지 못하게 한다.

    보정에는 방향 판정이 없었다 — 「郡治가 MAX_KM 밖이다」만 보고 무조건
    갈아탔다. 廣漢屬國은 그래서 앵커가 廣西 貴港(109.61, 23.10)으로 옮겨져
    있었다. 실제 廣漢 일대에서 1000km 넘게 떨어진 三國期(264~279) 동명 縣이다.

    이건 산출물의 anchor 필드에 그대로 남는다 — 縣 좌표는 하나도 안 변하므로
    縣만 보는 회귀로는 안 잡힌다.
    """

    @classmethod
    def setUpClass(cls):
        import json
        cls.places = {p['name']: p
                      for p in json.loads(OUT.read_text(encoding='utf-8'))['places']}

    def test_guanghan_shuguo_anchor_is_not_the_guangxi_homonym(self):
        got = tuple(self.places['廣漢屬國']['anchor'])
        self.assertNotEqual(
            (round(got[0], 3), round(got[1], 3)), (109.608, 23.099),
            '廣漢屬國 앵커가 廣西 貴港의 「陰平」(264~279)으로 되돌아갔다 — '
            '자기보정 방향 판정이 꺼졌다.')

    def test_shuguo_anchors_sit_near_their_parent_commandery(self):
        """屬國은 모군에서 갈라져 나온 단위다 — 앵커가 모군 곁을 벗어나면 틀렸다.

        임계값을 새로 만들지 않는다: 파서 자신이 縣 인정에 쓰는 MAX_KM 을
        그대로 쓴다. 지금 실측은 廣漢 0km · 犍為 0km 다.
        """
        build = _build()
        max_km = _max_km()
        for child, parent in (('廣漢屬國', '廣漢郡'), ('犍為屬國', '犍為郡')):
            with self.subTest(child=child):
                d = build.km(tuple(self.places[child]['anchor']),
                             tuple(self.places[parent]['anchor']))
                self.assertLessEqual(
                    d, max_km,
                    f'{child} 앵커가 {parent} 에서 {d:.0f}km 떨어져 있다 '
                    f'(MAX_KM={max_km}).')

if __name__ == '__main__':
    unittest.main()
