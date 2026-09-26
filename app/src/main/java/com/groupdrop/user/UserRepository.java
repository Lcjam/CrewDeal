package com.groupdrop.user;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * 시드 전용 원자적 생성. 조회 후 저장하면 두 인스턴스가 동시에 기동할 때 {@code users.email}
     * UNIQUE 위반으로 한쪽이 기동에 실패한다. 충돌은 DB가 0행으로 흡수한다.
     *
     * @return 1이면 새로 만들었고, 0이면 이미 있었다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO users (email, password_hash, display_name, role, created_at)
            VALUES (:email, :passwordHash, :displayName, :role, :createdAt)
            ON CONFLICT (email) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("email") String email,
                       @Param("passwordHash") String passwordHash,
                       @Param("displayName") String displayName,
                       @Param("role") String role,
                       @Param("createdAt") Instant createdAt);
}
