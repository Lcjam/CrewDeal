package com.groupdrop.user;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시드 계정 생성 (기획서 5장 — 인증은 시드 데이터로만 생성, 회원가입 화면 없음).
 * 프로파일 무관 실행.
 *
 * <p>여러 인스턴스가 빈 DB로 동시에 기동해도 멱등해야 한다. 조회 후 저장(find → save)은 두 인스턴스가
 * 모두 "없음"을 보고 INSERT해 UNIQUE 위반으로 한쪽 기동이 죽으므로, 계정과 프로필 모두
 * {@code INSERT … ON CONFLICT DO NOTHING} 한 문장으로 만든다. 먼저 커밋되지 않은 같은 키가 있으면
 * PostgreSQL이 그 트랜잭션의 종료를 기다린 뒤 0행으로 끝낸다.
 */
@Component
public class SeedDataRunner implements ApplicationRunner {

    private static final String SEED_PASSWORD = "groupdrop123!";

    static final List<SeedAccount> DEFAULT_ACCOUNTS = List.of(
            new SeedAccount("admin@groupdrop.test", UserRole.ADMIN, null),
            new SeedAccount("influencer@groupdrop.test", UserRole.INFLUENCER, "김인플"),
            new SeedAccount("supplier@groupdrop.test", UserRole.SUPPLIER, "굿즈컴퍼니"),
            new SeedAccount("buyer1@groupdrop.test", UserRole.BUYER, null),
            new SeedAccount("buyer2@groupdrop.test", UserRole.BUYER, null));

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
        seed(DEFAULT_ACCOUNTS);
    }

    /** 테스트가 고유 계정으로 동시 실행을 재현할 수 있도록 계정 목록을 받는다. 운영 경로는 {@link #run}뿐이다. */
    @Transactional
    void seed(List<SeedAccount> accounts) {
        Instant now = Instant.now(clock);
        // 모든 시드 계정의 비밀번호가 같으므로 한 번만 인코딩한다 (BCrypt는 기동 시간에 비싸다).
        String passwordHash = passwordEncoder.encode(SEED_PASSWORD);
        for (SeedAccount account : accounts) {
            userRepository.insertIfAbsent(account.email(), passwordHash, localPart(account.email()),
                    account.role().name(), now);
            if (account.role() == UserRole.INFLUENCER) {
                influencerRepository.insertIfAbsentForEmail(account.email(), account.profileName(), now);
            } else if (account.role() == UserRole.SUPPLIER) {
                supplierRepository.insertIfAbsentForEmail(account.email(), account.profileName(), now);
            }
        }
    }

    private String localPart(String email) {
        return email.substring(0, email.indexOf('@'));
    }

    /** {@code profileName}은 INFLUENCER·SUPPLIER의 프로필 이름이며 다른 역할에서는 쓰지 않는다. */
    record SeedAccount(String email, UserRole role, String profileName) {

        SeedAccount {
            boolean needsProfile = role == UserRole.INFLUENCER || role == UserRole.SUPPLIER;
            if (needsProfile && (profileName == null || profileName.isBlank())) {
                throw new IllegalArgumentException("INFLUENCER·SUPPLIER 시드 계정은 프로필 이름이 필요합니다: " + email);
            }
        }
    }
}
