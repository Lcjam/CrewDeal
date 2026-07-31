package com.groupdrop.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    boolean existsByUserId(Long userId);

    Optional<Supplier> findByUserId(Long userId);
}
