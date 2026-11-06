package com.hanstep.user.repository;

import com.hanstep.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByExternalUserKey(String externalUserKey);
}
