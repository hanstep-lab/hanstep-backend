package com.hanstep.user.repository;

import com.hanstep.user.domain.Language;
import com.hanstep.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByExternalUserKey(String externalUserKey);

    @Query("select u.preferredLanguage from User u where u.id = :id")
    Optional<Language> findPreferredLanguageById(Long id);

    /** 서버가 만드는 화면 문구 언어. ko·사용자 없음이면 false(한국어 문장), en·vi·zh 면 true(영어 문장). */
    default boolean prefersEnglish(Long userId) {
        return findPreferredLanguageById(userId).map(language -> language != Language.ko).orElse(false);
    }
}
