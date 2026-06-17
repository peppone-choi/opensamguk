-- V15 — board_post.author_icon 작성자 초상 컬럼 (P1-017, W0-8 infra 공유 widen).
--
-- PHP 정본 hwe/sql/schema.sql `board` 테이블(:157): `author_icon VARCHAR(128) NULL DEFAULT NULL`.
-- j_board_article_add.php:65,73이 글 작성 시 작성자의 64px 초상 경로를 저장하고
-- BoardArticle.vue:15-17이 렌더한다. NULL 허용 — 아이콘 없는 글(NPC/미설정)은 NULL.
ALTER TABLE board_post ADD COLUMN author_icon text;
