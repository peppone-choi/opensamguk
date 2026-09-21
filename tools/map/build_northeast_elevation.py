#!/usr/bin/env python3
"""Add real NOAA ETOPO1 elevations in the reviewed northeast extension.

2026-09-21: the live release no longer carries the northeast frame — these
assets are frozen witnesses for the 843x864 releases (1194, 1341), which saved
worlds still load. --check therefore binds to the extension ledger's pinned
projection, not to the live han-tiles frame, and --csv-dir refuses to rebuild
unless han-tiles is actually on that frame.

The original metres PNG is kept as the lowland stage's immutable witness.
--csv-dir uses four 2-arc-minute ERDDAP bands (41.4..53.95 N, 118.9..139.2 E).
--check verifies retained cells, pinned dimensions, PNG pins, and DEM levels
without network access. New source bytes are identified in the manifest.
"""
import argparse,hashlib,json,sys
from pathlib import Path
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from tools.map import build_elevation_grid as E
from tools.map.korea_map_extension import NORTH_ROWS,RASTER
BASE='han-world-v3-metres.png';EXPANDED='han-world-v3-northeast-metres.png';LEVEL='han-world-v3-levels.png'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--csv-dir',type=Path);ap.add_argument('--check',action='store_true');args=ap.parse_args()
 t=json.loads((ROOT/'data/map/han-tiles.json').read_text());out=ROOT/'web/game/public/map/elevation';old=np.array(Image.open(out/BASE),dtype=np.int32)-32768
 # 동결 843x864 프레임은 확장 원장에 핀돼 있다. 살아 있는 han-tiles 프레임과 무관하게 이 값으로 검사한다.
 p=json.loads(RASTER.read_text())['projection']
 if args.check:
  d=json.loads((out/'manifest.json').read_text());a=np.array(Image.open(out/EXPANDED),dtype=np.int32)-32768
  assert a.shape==(p['rows'],p['cols']) and d['projection']==p
  assert np.array_equal(a[NORTH_ROWS:NORTH_ROWS+old.shape[0],:old.shape[1]],old)
  assert d['metreGrid']['sha256']==sha(out/EXPANDED) and d['levelGrid']['sha256']==sha(out/LEVEL)
  levels=E.quantise(E.block_reduce_mean(a,2)[0],E.LEVEL_LADDER)
  assert np.array_equal(levels,np.array(Image.open(out/LEVEL)))
  for name in [BASE,EXPANDED,LEVEL,'manifest.json']:
   assert (out/name).read_bytes()==(ROOT/'web/gateway/public/map/elevation'/name).read_bytes()
  print('OK northeast DEM: real ETOPO1; original cells retained; levels/pins match');return 0
 if not args.csv_dir:ap.error('--csv-dir or --check required')
 if t['_meta']['projection']!=p:ap.error('han-tiles is not on the northeast frame; these assets are frozen witnesses now')
 paths=sorted(args.csv_dir.glob('b*.csv'));assert len(paths)==4
 lats,lons,grid,missing=E.parse_csv(paths);assert missing==0,missing
 a=np.round(E.sample_projection(lats,lons,grid,E.Proj(p))).astype(np.int32)
 # Outside the added geographic scope there is no terrain; don't extrapolate DEM.
 rr,cc=np.indices(a.shape);lat=p['y1']+p['pad']-(rr+.5)*p['cell'];lon=((cc+.5)*p['cell']+p['x0']-p['pad'])/p['k']
 a[(lat<41.4)|(lon<118.9)]=0
 a[NORTH_ROWS:NORTH_ROWS+old.shape[0],:old.shape[1]]=old
 raw=(a+32768).astype('<u2');Image.frombytes('I;16',(p['cols'],p['rows']),raw.tobytes()).save(out/EXPANDED,optimize=True)
 levels,rows,cols=E.block_reduce_mean(a,2);levels=E.quantise(levels,E.LEVEL_LADDER);Image.fromarray(levels).save(out/LEVEL,optimize=True)
 d=json.loads((out/'manifest.json').read_text());d.update(projection=p,generator='tools/map/build_northeast_elevation.py')
 d['source']['northeastExtension']=dict(retrieved='2026-09-20',bounds=dict(south=41.4,north=53.95,west=118.9,east=139.2),sourceFiles=[dict(file=f.name,sha256=sha(f)) for f in paths],originalMetresSha256=sha(out/BASE))
 d['metreGrid'].update(file=EXPANDED,cols=p['cols'],rows=p['rows'],min=int(a.min()),max=int(a.max()),sha256=sha(out/EXPANDED))
 d['levelGrid'].update(cols=cols,rows=rows,sha256=sha(out/LEVEL),histogram={str(v):int(n) for v,n in zip(*np.unique(levels,return_counts=True))})
 (out/'manifest.json').write_text(json.dumps(d,ensure_ascii=False,indent=1)+'\n')
 for name in [BASE,EXPANDED,LEVEL,'manifest.json']:(ROOT/'web/gateway/public/map/elevation'/name).write_bytes((out/name).read_bytes())
 print('Wrote northeast elevation',p['cols'],p['rows']);return 0
if __name__=='__main__':raise SystemExit(main())
