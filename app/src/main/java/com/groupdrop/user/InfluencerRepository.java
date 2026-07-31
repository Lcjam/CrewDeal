package com.groupdrop.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    boolean existsByUserId(Long userId);

    Optional<Influencer> findByUserId(Long userId);
}
