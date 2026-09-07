package opensamguk.gateway.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** 공지 한 건(공개·관리 공통). `body` 는 평문이며 줄바꿈은 클라이언트가 pre-line 으로 그린다. */
data class NoticeResponse(
    val id: Long,
    val title: String,
    val body: String,
    val pinned: Boolean,
    val publishedAt: String,
    val deleted: Boolean,
)

data class NoticeListResponse(val notices: List<NoticeResponse>)

data class NoticeUpsertRequest(
    @field:NotBlank(message = "제목을 입력해주세요")
    @field:Size(max = 120, message = "제목은 120자를 넘을 수 없습니다")
    val title: String,
    @field:NotBlank(message = "내용을 입력해주세요")
    @field:Size(max = 4000, message = "내용은 4000자를 넘을 수 없습니다")
    val body: String,
    val pinned: Boolean = false,
)

data class NoticePinRequest(val pinned: Boolean)
