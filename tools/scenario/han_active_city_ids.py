# User-reviewed retirements from the deployed 1194 roster. Never recycle these IDs.
RETIRED_CURRENT_CITY_IDS = frozenset([1143, 1148, 1157, 1159, 1160, 1161, 1162, 1163, 1164, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194])

# 2026-09-21 조선반도·만주 정리로 거둔 동결 1341 판의 취락 번호. korea-retired-settlements-v1 의
# numericIdsReserved 가운데 1194 초과분(1195–1341, 147개)이다. 위 26개와 똑같이 다시 쓰지 않는다.
WITHDRAWN_1341_RELEASE_IDS = frozenset(range(1195, 1342))

# 은퇴 이전에 동결된 판은 1 부터 명부 수까지 연속이다(1194·1341 판).
PRE_RETIREMENT_CONTIGUOUS_COUNTS = frozenset({1194, 1341})


def active_numeric_ids(count: int) -> list[int]:
    """명부 수 `count` 인 판의 도시 번호. 은퇴 뒤의 판은 예약 번호를 건너뛰며 앞에서부터 센다.

    종전 구현은 `count == 1168` 한 경우만 은퇴 id 를 걸렀고 그 밖에서는 `range(1, count+1)` 을
    돌려주어 「절대 재사용 금지」 번호를 되썼다. 첫 수정은 1194 이하의 26 개만 건너뛰어 결손 縣 56 곳이
    1341 판이 다른 취락에 줬던 1342–1397 를 받았다(korea_place_corrections 테스트가 잡았다).
    지금은 예약 173 개를 모두 건너뛴다 — 1224 판의 새 縣은 1342–1397 이다.
    1168 판은 종전과 같은 목록(1194 − 26), 1194·1341 판은 종전과 같은 연속 목록을 준다.
    """
    if count < 0:
        raise ValueError("count must not be negative")
    if count < 1168 or count in PRE_RETIREMENT_CONTIGUOUS_COUNTS:
        return list(range(1, count + 1))
    reserved = RETIRED_CURRENT_CITY_IDS | WITHDRAWN_1341_RELEASE_IDS
    ids: list[int] = []
    candidate = 1
    while len(ids) < count:
        if candidate not in reserved:
            ids.append(candidate)
        candidate += 1
    return ids
