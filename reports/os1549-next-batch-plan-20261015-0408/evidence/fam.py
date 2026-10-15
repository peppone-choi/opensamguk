import sys, json, collections
repo=sys.argv[1]; names=sys.argv[2].split(',')
from pathlib import Path
root=Path(repo)
codes=(1010,1020,1021,1030,1031,1040,1041,1050,1060,1070,1080,1090,1100,1110,1120)
seen=collections.defaultdict(set)
for c in codes:
    s=json.load(open(root/'data/archive/scenarios'/f'scenario_{c}.json'))
    nat=s['nation']
    for g in ('general','general_ex','general_neutral'):
        for r in s.get(g,[]):
            if r[1] in names:
                seen[(r[1],r[9],r[10],tuple(r[5:8]),g,r[2])].add(c)
for k,v in sorted(seen.items()): print(k, sorted(v))
