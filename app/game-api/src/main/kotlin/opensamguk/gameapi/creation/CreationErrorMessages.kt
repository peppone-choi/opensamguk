package opensamguk.gameapi.creation

object CreationErrorMessages {
    private val messages = mapOf(
        "AUTH_REQUIRED" to "로그인이 필요합니다.",
        "WORLD_CHANGED" to "서버가 바뀌었습니다. 다시 선택해 주세요.",
        "GENERAL_ALREADY_OWNED" to "이미 이 서버에 장수가 있습니다.",
        "REQUEST_ID_REUSED" to "같은 요청 번호로 다른 내용을 보낼 수 없습니다.",
        "INVALID_REQUEST" to "입력 내용을 확인해 주세요.",
        "INVALID_NAME" to "이름은 1–12글자이며 한글·한자·라틴 글자와 글자 사이의 한 칸 또는 가운뎃점만 쓸 수 있습니다.",
        "NAME_ALREADY_USED" to "이미 쓰는 이름입니다. 다른 이름을 선택해 주세요.",
        "INVALID_NATIVE_COUNTY" to "시작할 수 없는 본관입니다. 다른 현을 선택해 주세요.",
        "INVALID_STATS" to "능력치는 각각 20–85이고 합계가 정확히 300이어야 합니다.",
        "INVALID_IDEOLOGY" to "목록에 있는 주의를 선택해 주세요.",
        "INVALID_TRAIT" to "목록에 있는 개성을 선택해 주세요.",
        "ROLE_UNAVAILABLE" to "이 시작 역할은 현재 세계에서 선택할 수 없습니다.",
        "ROLE_CAP_REACHED" to "이 시작 역할의 사람 자리가 가득 찼습니다.",
        "HISTORICAL_PERSON_NOT_APPEARED" to "아직 등장하지 않은 인물입니다.",
        "HISTORICAL_PERSON_UNAVAILABLE" to "이 인물은 지금 선택할 수 없습니다. 목록을 다시 읽어 주세요.",
        "CREATION_POLICY_UNAVAILABLE" to "장수 만들기가 아직 열리지 않았습니다. 잠시 후 다시 확인해 주세요.",
        "CREATION_REQUEST_NOT_FOUND" to "생성 요청을 찾을 수 없습니다.",
    )

    fun forCode(code: String): String = messages[code] ?: "장수 생성 결과를 확인할 수 없습니다."
}
