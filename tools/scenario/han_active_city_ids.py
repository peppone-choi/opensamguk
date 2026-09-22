# User-reviewed retirements from the deployed 1194 roster. Never recycle these IDs.
RETIRED_CURRENT_CITY_IDS = frozenset([1143, 1148, 1157, 1159, 1160, 1161, 1162, 1163, 1164, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194])

def active_numeric_ids(count: int) -> list[int]:
    if count == 1168:
        return [i for i in range(1, 1195) if i not in RETIRED_CURRENT_CITY_IDS]
    return list(range(1, count + 1))
