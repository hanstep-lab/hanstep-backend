package com.hanstep.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트 전용 사용자. 데모 사용자(demo-e9-ph-reyes) 상태에 기대지 않도록 테스트마다 새로 만든다. */
public final class TestUsers {

    private TestUsers() {
    }

    /** users 에 한 명 넣고 id 를 돌려준다. @Transactional 테스트 안에서 부르면 롤백으로 지워진다. */
    public static long create(JdbcTemplate jdbc, String externalUserKey, String displayName) {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES (?, ?)", externalUserKey, displayName);
        return jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, externalUserKey);
    }
}
