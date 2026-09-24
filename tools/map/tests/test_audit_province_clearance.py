"""Red probes for the map clearance and movement gates."""
import unittest
from pathlib import Path
import sys

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from tools.map.audit_province_clearance import audit  # noqa: E402


def fixture(extra_cells, extra_level):
    owner = np.repeat(np.arange(11, dtype=np.int32), 12)
    owner = np.repeat(owner[None, :], 44, axis=0)
    for row, col in extra_cells:
        owner[row, col] = 11
    pairs = set()
    for first, second in ((owner[1:], owner[:-1]), (owner[:, 1:], owner[:, :-1])):
        for a, b in zip(first.ravel(), second.ravel()):
            if a != b:
                pairs.add(tuple(sorted((int(a), int(b)))))
    flat = owner.ravel().tolist()
    runs = []
    for value in flat:
        if runs and runs[-1][0] == value:
            runs[-1][1] += 1
        else:
            runs.append([value, 1])
    provinces = [dict(id=f'P{i}', parentRegionId='J0', displayName=f'P{i}',
                      geometryBasis='TEST') for i in range(12)]
    cities = [dict(id=i + 1, name=f'C{i}', level=i + 1, spatialProvinceId=f'P{i}')
              for i in range(11)]
    cities.append(dict(id=12, name='probe', level=extra_level, spatialProvinceId='P11'))
    return ({'_meta': {'rows': 44, 'cols': 132}, 'owner': runs,
             'provinceRecords': provinces,
             'adjacency': {'county': [dict(a=a, b=b) for a, b in sorted(pairs)]}},
            {'cities': cities, 'seaRoutes': []})


class ClearanceRedProbeTest(unittest.TestCase):
    def test_one_cell_wide_county_is_narrow(self):
        tiles, world = fixture([(row, 11) for row in range(18, 27)], 5)
        result = audit(tiles, world)
        self.assertIn('P11', {row['provinceId'] for row in result['narrow']})
        self.assertIn('P11', {row['provinceId'] for row in result['growthSpaceMissing']})

    def test_eight_cell_enclave_is_rejected_at_every_city_rank_group(self):
        enclave = [(row, col) for row in range(19, 22) for col in range(4, 7)]
        enclave.remove((19, 4))
        for level in (1, 2, 3, 4, 5):
            with self.subTest(level=level):
                tiles, world = fixture(enclave, level)
                result = audit(tiles, world)
                self.assertIn('P11', {row['provinceId'] for row in result['narrow']})
                self.assertIn('P11', {row['provinceId'] for row in result['surrounded']})

    def test_strategic_city_at_inner_dead_end_is_rejected(self):
        tiles, world = fixture([(row, 11) for row in range(18, 27)], 2)
        tiles['adjacency']['county'] = [edge for edge in tiles['adjacency']['county']
                                        if 11 not in (edge['a'], edge['b'])]
        tiles['adjacency']['county'].append({'a': 0, 'b': 11})
        result = audit(tiles, world)
        self.assertIn('P11', {row['provinceId'] for row in result['strategicDeadEnds']})


if __name__ == '__main__':
    unittest.main()
