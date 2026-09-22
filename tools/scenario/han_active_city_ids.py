# User-reviewed retirements from the deployed 1194 roster. Never recycle these IDs.
RETIRED_CURRENT_CITY_IDS = frozenset([1143, 1148, 1157, 1159, 1160, 1161, 1162, 1163, 1164, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194])

def active_numeric_ids(count: int) -> list[int]:
    """앞에서부터 은퇴 id 를 건너뛰며 `count` 개를 준다.

    종전 구현은 `count == 1168` 한 경우만 은퇴 id 를 걸렀고 그 밖에서는 `range(1, count+1)` 을
    돌려주어 **「절대 재사용 금지」 라고 적어 둔 26 개를 되썼다.** 명부가 1168 을 넘는 첫 추가
    (결손 縣 60 곳, 1228)에서 그게 드러났다 — 1143·1148·1157·1159–1164 가 새 縣에 배정됐고
    materialize 가 「numeric IDs must follow every earlier append」 로 걸렀다.
    이 구현은 count == 1168 에서 종전과 같은 목록을 준다(1194 − 은퇴 26 = 1168).
    """
    if count < 0:
        raise ValueError("count must not be negative")
    ids: list[int] = []
    candidate = 1
    while len(ids) < count:
        if candidate not in RETIRED_CURRENT_CITY_IDS:
            ids.append(candidate)
        candidate += 1
    return ids
