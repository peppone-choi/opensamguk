#!/usr/bin/env python3
import sys,json,hashlib
from pathlib import Path
import numpy as np
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.map.build_terrain_grid import Proj,rasterize,stroke_lines,REGION_TERRAIN
from tools.map.korea_map_extension import NORTH_ROWS,EAST_COLS,RASTER
import argparse
parser=argparse.ArgumentParser(description='Rebuild the pinned northeast Natural Earth raster.')
parser.add_argument('--natural-earth', type=Path, required=True)
parser.add_argument('--check', action='store_true')
args=parser.parse_args()
base=json.loads(RASTER.read_text())['baseProjection'];new=dict(base,rows=base['rows']+NORTH_ROWS,cols=base['cols']+EAST_COLS,y1=base['y1']+NORTH_ROWS*base['cell']);p=Proj(new)
ne=args.natural_earth
bbox=(118,141,41,55);land=rasterize(json.load(open(ne/'ne_50m_land.geojson'))['features'],p,bbox);t=np.zeros(land.shape,dtype=np.uint8);t[land]=1
feats=json.load(open(ne/'ne_10m_geography_regions_polys.geojson'))['features']
for cls,value in REGION_TERRAIN:
 subset=[f for f in feats if f['properties'].get('FEATURECLA')==cls]
 t[rasterize(subset,p,bbox)&land]=value
lakes=rasterize(json.load(open(ne/'ne_50m_lakes.geojson'))['features'],p,bbox);t[lakes]=4
stroke_lines(t,p,str(ne/'ne_50m_rivers_lake_centerlines.geojson'),bbox,3)
rr,cc=np.indices(t.shape);lon=(cc*new['cell']+new['x0']-new['pad'])/new['k'];lat=new['y1']+new['pad']-rr*new['cell']
# Scope is NE Asian land east of Greater Khingan and the adjacent coast.
t[(lon<119)|(lat<41.5)]=9
# Original footprint is a placeholder; apply() overwrites it from its reviewed input.
t[NORTH_ROWS:,:base['cols']]=9
sources=[dict(path=f.name,sha256=hashlib.sha256(f.read_bytes()).hexdigest()) for f in sorted(ne.glob('*.geojson')) if f.name in ['ne_50m_land.geojson','ne_50m_lakes.geojson','ne_50m_rivers_lake_centerlines.geojson','ne_10m_geography_regions_polys.geojson']]
d=dict(schemaVersion=1,baseProjection=base,projection=new,sourceUrl='https://www.naturalearthdata.com/about/terms-of-use/',license='PUBLIC_DOMAIN',sources=sources,terrain=[''.join(map(str,row)) for row in t.tolist()])
encoded=json.dumps(d,ensure_ascii=False,separators=(',',':'))+'\n'
if args.check:
 print('OK' if RASTER.read_text()==encoded else 'STALE', RASTER)
 raise SystemExit(RASTER.read_text()!=encoded)
RASTER.write_text(encoded)
print(new,RASTER)
