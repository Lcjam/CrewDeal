package com.groupdrop.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.TestcontainersConfiguration;
import com.groupdrop.user.SeedDataRunner.SeedAccount;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 두 인스턴스가 빈 DB로 동시에 기동하는 상황(2026-09-25 릴리스 게이트의 app2 exit 1)을 한 JVM에서 재현한다.
 * 스레드마다 별도 트랜잭션·커넥션으로 같은 계정 목록을 동시에 시드하고, 예외 없이 계정·프로필이 정확히 하나씩
 * 생기는지 고정한다. 매 반복마다 새 이메일을 써서 항상 "아직 없음"에서 경합이 시작되게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeedDataConcurrencyTest {

    private static final int INSTANCES = 8;

    @Autowired
    private SeedDataRunner seedDataRunner;

    @Autowired
    private JdbcTemplate jdbc;

    /** 같은 Testcontainers DB를 쓰는 다른 테스트의 {@code LIMIT 1} 조회에 경합용 프로필이 섞이지 않게 지운다. */
    @AfterEach
    void removeRaceAccounts() {
        jdbc.update("DELETE FROM influencers WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'race-%')");
        jdbc.update("DELETE FROM suppliers WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'race-%')");
        jdbc.update("DELETE FROM users WHERE email LIKE 'race-%'");
    }

    @RepeatedTest(5)
    void 동시에_시드해도_예외_없이_계정과_프로필이_하나씩만_생긴다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        List<SeedAccount> accounts = List.of(
                new SeedAccount("race-admin-" + suffix + "@groupdrop.test", UserRole.ADMIN, null),
                new SeedAccount("race-influencer-" + suffix + "@groupdrop.test", UserRole.INFLUENCER, "경합인플"),
                new SeedAccount("race-supplier-" + suffix + "@groupdrop.test", UserRole.SUPPLIER, "경합공급사"),
                new SeedAccount("race-buyer-" + suffix + "@groupdrop.test", UserRole.BUYER, null));

        CountDownLatch ready = new CountDownLatch(INSTANCES);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(INSTANCES)) {
            for (int i = 0; i < INSTANCES; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    seedDataRunner.seed(accounts);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS); // 한 스레드라도 UNIQUE 위반을 던지면 여기서 실패한다
            }
        }

        for (SeedAccount account : accounts) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ? AND role = ?",
                    Long.class, account.email(), account.role().name())).isEqualTo(1L);
        }
        assertThat(profileCount("influencers", accounts.get(1).email())).isEqualTo(1L);
        assertThat(profileCount("suppliers", accounts.get(2).email())).isEqualTo(1L);
    }

    private long profileCount(String table, String email) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table
                + " p JOIN users u ON u.id = p.user_id WHERE u.email = ?", Long.class, email);
    }
}
