# OPENSAM-79 gateway community-board contract

## Scope and source of truth

This is a new gateway-account community surface, not a port of the legacy game-world
`board_post` and `board_comment` model. The explicit OPENSAM-79 product contract is
therefore the source of truth for this feature. PHP byte parity, game RNG, world state,
and daemon/JDBC-write rules do not apply to the new tables or HTTP routes.

The legacy world-board tables remain authoritative for in-game board behavior and are
not widened or reused. Gateway ownership is `users.id` only, so a community post never
depends on a world, general, or nation. This separation is necessary for one gateway
account to use the lobby independently of any selected game world.

## Persistent contract

`V40__gateway_board.sql` creates only `gateway_board_post` and
`gateway_board_comment`. It gives both post and comment author/deleter references an
`ON DELETE SET NULL` account foreign key plus a display-name snapshot. There is no
`world_id` column. The migration uses `IF NOT EXISTS` for tables and indexes, and the
PostgreSQL integration test reruns the V40 SQL after Flyway startup before validating
that Flyway records exactly one successful V40 history row.

The feed index order is pinned first, then pin time, creation time, and id. Category
filtering uses the same order after its fixed category prefix. Soft deletion clears a
post pin, preserves the row for stable pagination, and masks it on all public reads.

## HTTP and rendering contract

Public reads are `GET /board/posts` and `GET /board/posts/{postId}`. Writes require a
gateway JWT: post/comment creation is authenticated, deletion is author-or-ADMIN, and
pinning is ADMIN-only. A post list returns
`{content,page,size,totalElements,totalPages}`; post items return
`{id,category,authorName,title,contentHtml,pinned,canDelete,deleted,createdAt,updatedAt}`.
Comments return `{id,authorName,content,canDelete,deleted,createdAt}`. `canDelete` is
computed for the optional authenticated gateway principal: it is true only for the
author or an ADMIN and false for anonymous readers. Both public read responses send
`Vary: Authorization` so a shared cache cannot reuse an anonymous capability response
for an authenticated reader (or the reverse). A malformed or expired Bearer value on
a public read is treated as anonymous rather than causing a 401.

Post requests are plain text. The backend HTML-escapes the body before storing it as
`contentHtml`, emitting only normalized `<br>` tags. Consumers may render only that
server-owned field as HTML. Title, author name, and comment content are plain-text
fields and must be rendered as text. Deleted posts expose fixed Korean title/body
masks and no comments; deleted comments expose a fixed Korean comment mask.

## Evidence plan

The focused gateway test suite covers anonymous public-read and mutation denial,
authenticated post/comment creation, owner/admin authorization, pin-first pagination,
stored XSS escaping, and soft-delete masks. The Testcontainers PostgreSQL integration
test covers V40 schema shape, account-only identity, indexes, raw DDL idempotence, and
Flyway history idempotence. A separate independent review is required before this work
is eligible for merge.
