import sys, json, collections
repo = sys.argv[1]
sys.path.insert(0, repo)
from pathlib import Path
import tools.scenario.materialize_hwiha_scenarios as m
root = Path(repo)
city_ids, template = m.inputs(root)
rtk = m.load(root / m.PACKAGED / "scenario_3190.json")
fp = collections.defaultdict(lambda: {"codes": [], "groups": set(), "nations": set(), "rows":0})
total_unres = 0
full_bound = set()
for code in m.ACTIVE_CODES:
    src = m.load(root / m.ARCHIVE / f"scenario_{code}.json")
    placed = m.materialize(code, src, city_ids, template)
    nations = placed["nation"]
    index = {"people": {r[1]: r for r in rtk["general"]}, "policies": {r["name"]: r for r in rtk["personPolicies"]}}
    for group in ("general","general_ex","general_neutral"):
        for row in placed.get(group, []):
            b = m.reviewed_rtk14_binding(row, index, code, group)
            if b is None:
                total_unres += 1
                k = (row[1], row[9], row[10], tuple(row[5:8]))
                d = fp[k]; d["codes"].append(code); d["groups"].add(group); d["rows"] += 1
                d["nations"].add(nations[row[3]-1][0] if row[3] else "재야")
                d["officerLevel"] = row[8]
                d["picture"] = row[2]
            else:
                full_bound.add((row[1], row[9], row[10]))
ids = {(k[0],k[1],k[2]) for k in fp}
print("unresolved rows", total_unres, "unique ident", len(ids), "unique fp", len(fp), "bound unique", len(full_bound))
_, unres, miss = m.prepared_materialization(root)
print("missing rulers", miss)
rtkn = {r[1]: r for r in rtk["general"]}
out=[]
for k,d in sorted(fp.items()):
    out.append(dict(name=k[0],birth=k[1],death=k[2],stats=list(k[3]),codes=d["codes"],groups=sorted(d["groups"]),nations=sorted(d["nations"]),rows=d["rows"],level=d["officerLevel"],srcPicture=d["picture"],rtkSameName=(rtkn[k[0]][:3]+rtkn[k[0]][5:8]+rtkn[k[0]][9:11]) if k[0] in rtkn else None))
json.dump(out, open(sys.argv[2],"w"), ensure_ascii=False, indent=1)
for o in out: print(o["name"],o["birth"],o["death"],o["stats"],o["codes"][0],len(o["codes"]),o["groups"],o["nations"][:3],o["level"],o["rtkSameName"])
