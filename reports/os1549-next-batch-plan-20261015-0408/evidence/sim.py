# In-memory simulation only: monkeypatches module dicts, writes no repo file.
import sys
repo=sys.argv[1]; sys.path.insert(0, repo)
from pathlib import Path
import tools.scenario.materialize_hwiha_scenarios as m
ALT=m.ALTERNATE_ARCHIVE_CODES
C={
 "조우":(("조우",199,278,(50,43,37)),("조우",10399,ALT)),
 "학맹300":(("학맹",156,300,(62,67,42)),("학맹",10810,(1021,1031))),
 "학맹196":(("학맹",156,196,(62,67,42)),("학맹",10810,(1041,))),
 "이적":(("이적",162,273,(29,24,84)),("이적",10019,ALT)),
 "공지":(("공지",178,197,(43,53,62)),("공지",10909,ALT)),
 "손환":(("손환",166,228,(82,73,76)),("손환",10245,ALT)),
 "장초":(("장초",168,196,(61,32,60)),("장초",10349,ALT)),
 "유주":(("유주",172,233,(67,78,42)),("유주",10073,ALT)),
 "조절":(("조절",190,260,(32,33,79)),("조절",10413,ALT)),
 "아들노숙":(("아들노숙",217,272,(73,53,74)),("노숙1",10973,ALT)),
 "유순":(("유순",172,255,(63,46,41)),("유순",10083,ALT)),
 "고담륭":(("고담륭",190,251,(35,24,76)),("고당륭",10957,ALT)),
 "장의":(("장의",153,215,(79,72,75)),("장의",10356,ALT)),
 "두씨":(("두씨",172,236,(26,41,52)),("두씨",10462,ALT)),
 "유씨":(("유씨",164,215,(8,8,44)),("유씨",10087,ALT)),
}
def run(keys):
    saved=dict(m.REVIEWED_RTK14_PROFILE_VARIANTS)
    try:
        for k in keys: m.REVIEWED_RTK14_PROFILE_VARIANTS[C[k][0]]=C[k][1]
        _,unres,_=m.prepared_materialization(Path(repo))
        rows=sum(len(v) for v in unres.values()); uniq=len({u for v in unres.values() for u in v})
        return f"OK rows={rows} unique={uniq}"
    except ValueError as e:
        return f"ValueError: {e}"
    finally:
        m.REVIEWED_RTK14_PROFILE_VARIANTS.clear(); m.REVIEWED_RTK14_PROFILE_VARIANTS.update(saved)
print("baseline", run([]))
for k in C: print(k, run([k]))
batch=sys.argv[2].split(',') if len(sys.argv)>2 else []
if batch: print("BATCH", batch, run(batch))
