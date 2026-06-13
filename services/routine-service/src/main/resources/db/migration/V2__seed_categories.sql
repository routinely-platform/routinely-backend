-- =============================================
-- routine-service 카테고리 시드 데이터
-- 시스템 제공 고정 카테고리 12개
-- =============================================

INSERT INTO categories (code, name, icon, display_order, is_active) VALUES
    ('EXERCISE',     '운동',        '🏃', 1,  true),
    ('READING',      '독서',        '📚', 2,  true),
    ('STUDY',        '공부/학습',   '📖', 3,  true),
    ('LANGUAGE',     '어학',        '🗣️', 4,  true),
    ('HEALTH',       '건강',        '💊', 5,  true),
    ('SLEEP',        '수면',        '😴', 6,  true),
    ('DIET',         '식습관',      '🥗', 7,  true),
    ('MEDITATION',   '명상/마음챙김','🧘', 8,  true),
    ('SELF_IMPROVE', '자기계발',    '🌱', 9,  true),
    ('PRODUCTIVITY', '생산성',      '⚡', 10, true),
    ('HOBBY',        '취미',        '🎨', 11, true),
    ('QUIT_HABIT',   '금연/금주',   '🚭', 12, true);
