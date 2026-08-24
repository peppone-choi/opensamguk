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
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DBF = ROOT / 'data/chgis-source/v6_time_pref_pts_utf_wgs84.dbf'

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


if __name__ == '__main__':
    unittest.main()
