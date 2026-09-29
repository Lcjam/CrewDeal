package com.groupdrop.user;

import com.groupdrop.common.GroupdropProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시드 계정 생성 (기획서 5장 — 인증은 시드 데이터로만 생성, 회원가입 화면 없음).
 * 기본값에서는 프로파일 무관하게 매 기동마다 실행한다.
 *
 * <p>운영 배포에서는 {@code GROUPDROP_SEED_ENABLED=false}로 시드를 끄고, 켜 둘 때는
 * {@code GROUPDROP_SEED_PASSWORD}로 비밀번호를 주입한다 (기본값은 데모 비밀번호).
 * 계정은 없을 때만 만들므로, 주입한 비밀번호는 새로 생성되는 계정에만 적용되고 이미 있는 계정의 비밀번호를
 * 바꾸지 않는다. 시드를 꺼도 이미 만들어진 계정은 지우지 않는다.
 *
 * <p>여러 인스턴스가 빈 DB로 동시에 기동해도 멱등해야 한다. 조회 후 저장(find → save)은 두 인스턴스가
 * 모두 "없음"을 보고 INSERT해 UNIQUE 위반으로 한쪽 기동이 죽으므로, 계정과 프로필 모두
 * {@code INSERT … ON CONFLICT DO NOTHING} 한 문장으로 만든다. 먼저 커밋되지 않은 같은 키가 있으면
 * PostgreSQL이 그 트랜잭션의 종료를 기다린 뒤 0행으로 끝낸다.
 */
@Component
public class SeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

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
    private final GroupdropProperties properties;

    public SeedDataRunner(UserRepository userRepository,
                           InfluencerRepository influencerRepository,
                           SupplierRepository supplierRepository,
                           PasswordEncoder passwordEncoder,
                           Clock clock,
                           GroupdropProperties properties) {
        this.userRepository = userRepository;
        this.influencerRepository = influencerRepository;
        this.supplierRepository = supplierRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.seedEnabled()) {
            log.info("groupdrop.seed-enabled=false: 시드 계정 생성을 건너뜁니다.");
            return;
        }
        seed(DEFAULT_ACCOUNTS);
    }

    /** 테스트가 고유 계정으로 동시 실행을 재현할 수 있도록 계정 목록을 받는다. 운영 경로는 {@link #run}뿐이다. */
    @Transactional
    void seed(List<SeedAccount> accounts) {
        Instant now = Instant.now(clock);
        // 모든 시드 계정의 비밀번호가 같으므로 한 번만 인코딩한다 (BCrypt는 기동 시간에 비싸다).
        String passwordHash = passwordEncoder.encode(properties.seedPassword());
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
