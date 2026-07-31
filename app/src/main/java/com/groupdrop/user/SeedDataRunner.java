package com.groupdrop.user;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시드 계정 생성 (기획서 5장 — 인증은 시드 데이터로만 생성, 회원가입 화면 없음).
 * 프로파일 무관 실행, email 존재 시 스킵하여 멱등하다.
 */
@Component
public class SeedDataRunner implements ApplicationRunner {

    private static final String SEED_PASSWORD = "groupdrop123!";

    private final UserRepository userRepository;
    private final InfluencerRepository influencerRepository;
    private final SupplierRepository supplierRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public SeedDataRunner(UserRepository userRepository,
                           InfluencerRepository influencerRepository,
                           SupplierRepository supplierRepository,
                           PasswordEncoder passwordEncoder,
                           Clock clock) {
        this.userRepository = userRepository;
        this.influencerRepository = influencerRepository;
        this.supplierRepository = supplierRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedPlainUser("admin@groupdrop.test", UserRole.ADMIN);
        seedInfluencer("influencer@groupdrop.test", "김인플");
        seedSupplier("supplier@groupdrop.test", "굿즈컴퍼니");
        seedPlainUser("buyer1@groupdrop.test", UserRole.BUYER);
        seedPlainUser("buyer2@groupdrop.test", UserRole.BUYER);
    }

    private void seedPlainUser(String email, UserRole role) {
        createUserIfAbsent(email, role);
    }

    private void seedInfluencer(String email, String name) {
        User user = createUserIfAbsent(email, UserRole.INFLUENCER);
        if (!influencerRepository.existsByUserId(user.getId())) {
            influencerRepository.save(new Influencer(user, name, Instant.now(clock)));
        }
    }

    private void seedSupplier(String email, String name) {
        User user = createUserIfAbsent(email, UserRole.SUPPLIER);
        if (!supplierRepository.existsByUserId(user.getId())) {
            supplierRepository.save(new Supplier(user, name, Instant.now(clock)));
        }
    }

    private User createUserIfAbsent(String email, UserRole role) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(new User(
                        email,
                        passwordEncoder.encode(SEED_PASSWORD),
                        localPart(email),
                        role,
                        Instant.now(clock))));
    }

    private String localPart(String email) {
        return email.substring(0, email.indexOf('@'));
    }
}
