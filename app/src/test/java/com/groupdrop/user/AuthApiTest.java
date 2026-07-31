package com.groupdrop.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

    private static final String SEED_PASSWORD = "groupdrop123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InfluencerRepository influencerRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private SeedDataRunner seedDataRunner;

    @Test
    void 로그인_성공_후_세션으로_me_조회_가능() throws Exception {
        MvcResult loginResult = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("admin@groupdrop.test", SEED_PASSWORD)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.email").value("admin@groupdrop.test"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.role").value("ADMIN"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").session(session))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.email").value("admin@groupdrop.test"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void 잘못된_비밀번호는_401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("admin@groupdrop.test", "wrong-password")))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 미인증_me_요청은_401이며_리다이렉트가_아니다() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void 로그아웃하면_세션이_무효화된다() throws Exception {
        MvcResult loginResult = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("buyer1@groupdrop.test", SEED_PASSWORD)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/logout").session(session))
                .andExpect(MockMvcResultMatchers.status().isNoContent());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").session(session))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void 시드_계정_5개가_역할과_함께_존재한다() {
        assertThat(userRepository.findByEmail("admin@groupdrop.test"))
                .isPresent()
                .get().extracting(User::getRole).isEqualTo(UserRole.ADMIN);

        assertThat(userRepository.findByEmail("influencer@groupdrop.test"))
                .isPresent()
                .get().extracting(User::getRole).isEqualTo(UserRole.INFLUENCER);

        assertThat(userRepository.findByEmail("supplier@groupdrop.test"))
                .isPresent()
                .get().extracting(User::getRole).isEqualTo(UserRole.SUPPLIER);

        assertThat(userRepository.findByEmail("buyer1@groupdrop.test"))
                .isPresent()
                .get().extracting(User::getRole).isEqualTo(UserRole.BUYER);

        assertThat(userRepository.findByEmail("buyer2@groupdrop.test"))
                .isPresent()
                .get().extracting(User::getRole).isEqualTo(UserRole.BUYER);

        User influencerUser = userRepository.findByEmail("influencer@groupdrop.test").orElseThrow();
        assertThat(influencerRepository.existsByUserId(influencerUser.getId())).isTrue();

        User supplierUser = userRepository.findByEmail("supplier@groupdrop.test").orElseThrow();
        assertThat(supplierRepository.existsByUserId(supplierUser.getId())).isTrue();
    }

    @Test
    void 시드_러너를_다시_실행해도_중복_생성되지_않는다() {
        long usersBefore = userRepository.count();
        long influencersBefore = influencerRepository.count();
        long suppliersBefore = supplierRepository.count();

        seedDataRunner.run(new DefaultApplicationArguments());
        seedDataRunner.run(new DefaultApplicationArguments());

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(influencerRepository.count()).isEqualTo(influencersBefore);
        assertThat(supplierRepository.count()).isEqualTo(suppliersBefore);
    }

    private String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
