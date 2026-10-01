package opensamguk.gameapi.council

import java.time.Instant

enum class CouncilRoom { MEETING, SECRET }
enum class CouncilArticleKind { GENERAL, OPERATION, NOTICE }

data class CouncilAccess(
    val canRead: Boolean,
    val canWrite: Boolean,
    val canNotice: Boolean,
    val reason: String? = null,
)

data class CouncilPortrait(val picture: String?, val imageServer: Int)

data class CouncilPerson(
    val generalId: Int,
    val name: String,
    val portrait: CouncilPortrait?,
    val role: String? = null,
)

data class CouncilMember(
    val generalId: Int,
    val name: String,
    val portrait: CouncilPortrait?,
    val active: Boolean?,
)

data class CouncilComment(
    val id: Int,
    val text: String,
    val author: CouncilPerson,
    val createdAt: Instant,
)

data class CouncilReaders(val read: List<CouncilPerson>, val total: Int?)

data class CouncilArticle(
    val id: Int,
    val kind: CouncilArticleKind,
    val title: String,
    val contentHtml: String,
    val author: CouncilPerson,
    val createdAt: Instant,
    val operationId: Int?,
    val readers: CouncilReaders?,
    val comments: List<CouncilComment>,
)

data class CouncilPage(
    val room: CouncilRoom,
    val access: CouncilAccess,
    val members: List<CouncilMember> = emptyList(),
    val articles: List<CouncilArticle> = emptyList(),
    val nextCursor: String? = null,
    val blockedReason: String? = null,
    val membershipState: String = "UNAVAILABLE",
)

data class CouncilFailure(val code: String, val reason: String)
