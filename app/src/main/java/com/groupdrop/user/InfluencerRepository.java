package com.groupdrop.user;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    boolean existsByUserId(Long userId);

    Optional<Influencer> findByUserId(Long userId);

    /** 시드 전용 원자적 생성 — {@code influencers.user_id} UNIQUE 충돌은 0행으로 흡수한다. */
    @Modifying
    @Query(value = """
            INSERT INTO influencers (user_id, name, created_at)
            SELECT u.id, :name, :createdAt FROM users u WHERE u.email = :email
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsentForEmail(@Param("email") String email,
                               @Param("name") String name,
                               @Param("createdAt") Instant createdAt);
}
