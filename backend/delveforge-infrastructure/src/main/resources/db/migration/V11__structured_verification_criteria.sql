-- 保留历史原文、顺序及身份；旧文本没有可信分类，不猜测或降低其业务含义。
ALTER TABLE evolution_step_section_item ADD COLUMN criterion_kind TEXT
    CHECK (criterion_kind IS NULL OR (section = 'verificationCriteria' AND criterion_kind IN
        ('BUILD_SUCCEEDS', 'FULL_TEST_SUITE_PASSES', 'SELECTED_TESTS_PASS', 'DIFF_CHECK_PASSES', 'UNSUPPORTED')));
UPDATE evolution_step_section_item SET criterion_kind = 'UNSUPPORTED' WHERE section = 'verificationCriteria';
