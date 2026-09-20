"""Reviewed additive geographic frame; old cells retain their world coordinates.

New terrain is a pinned Natural Earth raster. Northern river settlements are
explicit game geography, never an assertion of Buyeo/Yilou sovereign borders.
"""
import copy
import hashlib
import json
from pathlib import Path
import numpy as np
from tools.map.measure_province_seat_offset import expand_rle
from tools.map.world_province_geometry import _encode_runs
ROOT=Path(__file__).resolve().parents[2]
RASTER=ROOT/'data/curated/han/northeast-extension-terrain-v1.json'
NORTH_ROWS=174
EAST_COLS=96

def apply(document, owner, decision):
    artifact=json.loads(RASTER.read_text())
    if hashlib.sha256(RASTER.read_bytes()).hexdigest()!=decision['terrainSha256']:
        raise ValueError('unreviewed northeast terrain raster')
    old=document['_meta']['projection'];new=artifact['projection']
    if old!=artifact['baseProjection']:
        raise ValueError('northeast extension base projection changed')
    nr,nc=new['rows'],new['cols'];br,bc=old['rows'],old['cols']
    terrain=np.array([list(r) for r in artifact['terrain']])
    # Copy the original footprint byte-for-byte. No resampling or smoothing.
    terrain[NORTH_ROWS:NORTH_ROWS+br,:bc]=np.array([list(r) for r in document['terrain']])
    result=np.full((nr,nc),-1,dtype=np.int32)
    result[NORTH_ROWS:NORTH_ROWS+br,:bc]=owner
    added=(terrain!='0')&(terrain!='4')&(terrain!='9')
    added[NORTH_ROWS:NORTH_ROWS+br,:bc]=False
    cols=np.arange(nc)
    lon=(cols*new['cell']+new['x0']-new['pad'])/new['k']
    # Temporary allocation only; explicit settlement parent decisions below
    # replace these donor-region labels on every newly playable province.
    for jid,mask in [('X036',lon<127),('X039',lon>=127)]:
        pi=next(i for i,p in enumerate(document['provinceRecords']) if p['id']==jid)
        result[added & np.broadcast_to(mask,result.shape)]=pi
    for key in ('cities','juns','regions'):
        for row in document[key]:row['row']+=NORTH_ROWS
    for edge in document['adjacency']['commandery']:
        if isinstance(edge.get('ford'),dict) and 'row' in edge['ford']:edge['ford']['row']+=NORTH_ROWS
    # The legacy gameplay frame is a frozen compatibility object; leave it alone.
    for key in ('parentOwner','seatOwner'):
        base=expand_rle(document[key],br,bc);expanded=np.full((nr,nc),-1,dtype=np.int32)
        expanded[NORTH_ROWS:NORTH_ROWS+br,:bc]=base
        document[key]=_encode_runs(expanded)
    document['terrain']=[''.join(r) for r in terrain.tolist()]
    document['_meta'].update(rows=nr,cols=nc,projection=copy.deepcopy(new))
    return result

def base_frame(document):
    """Return the retained original cells for old, explicitly frozen witnesses."""
    doc=copy.deepcopy(document)
    if doc['_meta']['projection']['rows']==669:return doc
    artifact=json.loads(RASTER.read_text());p=artifact['baseProjection'];r,c=p['rows'],p['cols']
    doc['terrain']=[row[:c] for row in doc['terrain'][NORTH_ROWS:NORTH_ROWS+r]]
    doc['_meta'].update(rows=r,cols=c,projection=p)
    return doc
