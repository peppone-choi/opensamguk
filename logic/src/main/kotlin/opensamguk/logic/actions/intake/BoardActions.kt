package opensamguk.logic.actions.intake

/**
 * 회의실/기밀실 (board) intake 명령의 순수-로직 resolver — PHP `j_board_article_add.php` /
 * `j_board_comment_add.php`의 입력 검증 + 권한 게이트를 충실히 포팅한 것. 여기에는 Spring/DB/world가
 * 전혀 없다. side effect는 엔진의 `BoardHandler`가 소유한다(댓글을 위한 게시물 존재 여부 read +
 * social-content 채널로의 ChangeRecorder INSERT).
 *
 * 게시판 글/댓글은 social content(InMemoryTurnWorld의 game-state가 아님)이므로, betting/auction
 * 선례를 따라 ChangeRecorder INSERT 채널을 통해 flush된다 — 절대 game-api JPA write가 아니다
 * (locked rule 2).
 *
 * 권한: PHP는 `checkSecretPermission($me)`를 호출한다(기본값 `$checkSecretLimit = true`). 두 board
 * 게이트 — `permission < 0`과 `isSecret && permission < 2` — 는 officer_level-1 belong 분기가 0을
 * 반환하든 1을 반환하든 동일한 결과를 낸다(둘 다 `>= 0`이면서 `< 2`). 따라서 엔진은 `nation.secretlimit`
 * + `belong`을 배선하지 않고 `SecretPermission.check(me)`의 기본값(checkSecretLimit = false)으로
 * `permission`을 해석한다.
 *
 */
object BoardActions {

    // ── j_board_article_add.php ────────────────────────────────────────────────────────────────
    sealed interface ArticleOutcome {
        data class Denied(val reason: String) : ArticleOutcome
        /** board_post(nation_id = me.nationId, is_secret, author_general_id = me.id, author_name, title, content_html)로 INSERT. */
        data class Insert(
            val isSecret: Boolean,
            val title: String,
            val text: String,
            val kind: String = KIND_GENERAL,
            val voteId: Int? = null,
        ) : ArticleOutcome
    }

    /** 글 종류(ADR-LITE-049 14 회의실 탭 전체/표결/작전/공지). 공지는 수뇌부(permission >= 2)만, 표결은 voteId 필수. */
    const val KIND_GENERAL = "general"
    const val KIND_VOTE = "vote"
    const val KIND_OPERATION = "operation"
    const val KIND_NOTICE = "notice"
    val KINDS: Set<String> = setOf(KIND_GENERAL, KIND_VOTE, KIND_OPERATION, KIND_NOTICE)

    /**
     * `title`/`text`는 raw POST 값이다(null = 없음 — PHP `Util::getPost`는 null을 반환);
     * `permission`은 handler가 해석한 `checkSecretPermission(me)`이다. 행동 주체 장수의 id /
     * name / nation(INSERT 컬럼)은 handler가 read할 몫이며 — 게이트에는 필요하지 않다.
     */
    fun addArticle(
        isSecret: Boolean,
        rawTitle: String?,
        rawText: String?,
        permission: Int,
        kind: String = KIND_GENERAL,
        voteId: Int? = null,
    ): ArticleOutcome {
        if (rawTitle == null || rawText == null) return ArticleOutcome.Denied("올바르지 않은 입력입니다.")   // :40-44
        if (kind !in KINDS) return ArticleOutcome.Denied("올바르지 않은 입력입니다.")
        if (kind == KIND_VOTE && voteId == null) return ArticleOutcome.Denied("올바르지 않은 입력입니다.")
        val title = rawTitle.trim()                                                                       // :46
        val text = rawText.trim()                                                                         // :47
        if (title.isEmpty() && text.isEmpty()) return ArticleOutcome.Denied("제목과 내용이 둘다 비어있습니다.")  // :49-53
        if (permission < 0) return ArticleOutcome.Denied("국가에 소속되어있지 않습니다.")                      // :56-60
        if (isSecret && permission < 2) return ArticleOutcome.Denied("권한이 부족합니다. 수뇌부가 아닙니다.")   // :61-66
        if (kind == KIND_NOTICE && permission < 2) return ArticleOutcome.Denied("권한이 부족합니다. 수뇌부가 아닙니다.")
        return ArticleOutcome.Insert(isSecret, title, text, kind, if (kind == KIND_VOTE) voteId else null)
    }

    /** 기밀실 열람 기록 게이트 — 댓글 게이트와 같은 문자열(PHP checkSecretPermission). */
    fun readPermissionDeny(isSecret: Boolean, permission: Int): String? = commentPermissionDeny(isSecret, permission)

    // ── j_board_comment_add.php ────────────────────────────────────────────────────────────────
    // 게시물 존재 여부 read(SELECT board WHERE no AND nation_no → '게시물이 없습니다.')는 handler가
    // 텍스트 검증과 권한 게이트 사이에서 수행하는 DB 조회다(PHP 순서 :48-72).

    sealed interface CommentTextOutcome {
        data class Denied(val reason: String) : CommentTextOutcome
        data class Ok(val text: String) : CommentTextOutcome
    }

    /** 조회 이전(pre-lookup) 텍스트 검증 (PHP :40-50). `rawText` null = 없음. */
    fun validateCommentText(rawText: String?): CommentTextOutcome {
        if (rawText == null) return CommentTextOutcome.Denied("올바르지 않은 입력입니다.")   // :43-47 (text === null)
        val text = rawText.trim()                                                          // :50
        if (text.isEmpty()) return CommentTextOutcome.Denied("내용이 비어있습니다.")          // :52-56
        return CommentTextOutcome.Ok(text)
    }

    /** 조회 이후(post-lookup) 권한 게이트 (PHP :62-72), 조회된 게시물의 `is_secret`이 주어졌을 때. */
    fun commentPermissionDeny(isSecret: Boolean, permission: Int): String? {
        if (permission < 0) return "국가에 소속되어있지 않습니다."                 // :64-68
        if (isSecret && permission < 2) return "권한이 부족합니다. 수뇌부가 아닙니다." // :69-72
        return null
    }
}
