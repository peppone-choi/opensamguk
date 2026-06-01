-- P6 Task 6 — add nation.power column (PHP parity; func_gamerule.php Q4 writes nation.power).
ALTER TABLE nation ADD COLUMN power INTEGER NOT NULL DEFAULT 0;
