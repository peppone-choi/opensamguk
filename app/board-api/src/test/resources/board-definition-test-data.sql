-- Test profile uses Hibernate H2 schema without Flyway. PostgreSQL IT already has these rows.
INSERT INTO gateway_board_definition (board_key, name, sort_order, writable, created_at)
    SELECT 'NOTICE', '공지', 0, TRUE, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM gateway_board_definition WHERE board_key = 'NOTICE');
INSERT INTO gateway_board_definition (board_key, name, sort_order, writable, created_at)
    SELECT 'FREE', '자유', 1, TRUE, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM gateway_board_definition WHERE board_key = 'FREE');
INSERT INTO gateway_board_definition (board_key, name, sort_order, writable, created_at)
    SELECT 'SUGGESTION', '건의', 2, TRUE, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM gateway_board_definition WHERE board_key = 'SUGGESTION');
INSERT INTO gateway_board_definition (board_key, name, sort_order, writable, created_at)
    SELECT 'STRATEGY', '전략·공략', 3, TRUE, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM gateway_board_definition WHERE board_key = 'STRATEGY');
INSERT INTO gateway_board_definition (board_key, name, sort_order, writable, created_at)
    SELECT 'SERVER', '서버 이야기', 4, TRUE, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM gateway_board_definition WHERE board_key = 'SERVER');
INSERT INTO gateway_board_definition (board_key, name, sort_order, writable, created_at)
    SELECT 'CREATIVE', '창작·일지', 5, TRUE, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM gateway_board_definition WHERE board_key = 'CREATIVE');
