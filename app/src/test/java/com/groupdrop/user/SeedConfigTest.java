package com.groupdrop.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.groupdrop.TestcontainersConfiguration;
import com.groupdrop.common.GroupdropProperties;
import com.groupdrop.user.SeedDataRunner.SeedAccount;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시드 설정 스위치의 회귀 테스트. 새 컨텍스트를 띄우지 않도록 {@link SeedDataConcurrencyTest}와 같은 설정을
 * 쓰고(컨텍스트 캐시 공유), 러너는 프로퍼티만 바꿔 직접 조립한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeedConfigTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InfluencerRepository influencerRepository;
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private Clock clock;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE 'cfg-%'");
    }

    private GroupdropProperties properties(String key, String value) {
        Map<String, String> source = new HashMap<>();
        source.put("groupdrop." + key, value);
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("groupdrop", GroupdropProperties.class).get();
    }

    @Test
    void 기본값은_시드가_켜져_있고_기존_비밀번호를_쓴다() {
        GroupdropProperties defaults = properties("time-zone", "Asia/Seoul");
        assertThat(defaults.seedEnabled()).isTrue();
        assertThat(defaults.seedPassword()).isEqualTo("groupdrop123!");
        // 실제 컨텍스트의 시드 계정도 기본 비밀번호로 만들어져 있다.
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = 'admin@groupdrop.test'",
                String.class);
        assertThat(passwordEncoder.matches("groupdrop123!", hash)).isTrue();
    }

    @Test
    void seed_enabled가_false면_시드_저장소를_건드리지_않는다() {
        UserRepository users = mock(UserRepository.class);
        InfluencerRepository influencers = mock(InfluencerRepository.class);
        SupplierRepository suppliers = mock(SupplierRepository.class);
        SeedDataRunner runner = new SeedDataRunner(users, influencers, suppliers, passwordEncoder, clock,
                properties("seed-enabled", "false"));

        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(users, influencers, suppliers);
    }

    @Test
    void seed_password를_바꾸면_그_비밀번호로_인코딩된다() {
        SeedDataRunner runner = new SeedDataRunner(userRepository, influencerRepository, supplierRepository,
                passwordEncoder, clock, properties("seed-password", "custom-pw-1!"));
        String email = "cfg-buyer-" + UUID.randomUUID().toString().substring(0, 8) + "@groupdrop.test";

        transactionTemplate.executeWithoutResult(status ->
                runner.seed(List.of(new SeedAccount(email, UserRole.BUYER, null))));

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches("custom-pw-1!", hash)).isTrue();
        assertThat(passwordEncoder.matches("groupdrop123!", hash)).isFalse();
    }
}
