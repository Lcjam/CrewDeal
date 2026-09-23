package com.groupdrop.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.TestcontainersConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 시연 콘솔 정적 셸 계약 (프론트엔드 계획 §5.1·§9). 기존 {@code AdminConsoleAndOpenApiIntegrationTest}는
 * 수정하지 않고, 같은 패턴으로 새 콘솔(`/console/**`)만 고정한다.
 *
 * <p>임포트 스모크 테스트는 `static/console/**` 전체를 순회하므로 새 페이지가 추가돼도 손댈 필요가 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleShellIntegrationTest {

    /** `import x from '...'`, `import y, {z} from '...'` 형태. */
    private static final Pattern IMPORT_FROM = Pattern.compile(
            "import\\s+[^'\";]*?from\\s+['\"]([^'\"]+)['\"]");
    /** 부수효과 전용 `import '...'`. */
    private static final Pattern IMPORT_BARE = Pattern.compile(
            "import\\s+['\"]([^'\"]+)['\"]");
    /** `<script ...>` 태그 하나 전체(속성 순서 무관하게 뒤에서 type=module·src를 따로 검사한다). */
    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "<script\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_TYPE_MODULE = Pattern.compile(
            "\\btype\\s*=\\s*[\"']module[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_SRC = Pattern.compile(
            "\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 비인증_index_html은_200이고_text_html이다() throws Exception {
        mockMvc.perform(get("/console/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void 비인증_common_api_js는_200이고_javascript_타입이다() throws Exception {
        mockMvc.perform(get("/console/common/api.js"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String contentType = result.getResponse().getContentType();
                    assertThat(contentType).isNotNull();
                    assertThat(contentType.toLowerCase(java.util.Locale.ROOT)).contains("javascript");
                });
    }

    @Test
    void console와_console_슬래시는_index_html로_리다이렉트한다() throws Exception {
        mockMvc.perform(get("/console"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console/index.html"));

        mockMvc.perform(get("/console/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/console/index.html"));
    }

    @Test
    void 비인증_api_orders는_여전히_401이다() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"code\":\"AUTHENTICATION_REQUIRED\"")));
    }

    /**
     * `static/console/**`를 전부 순회해 `.html`·`.js` 파일이 참조하는 상대 import 경로가 모두 200인지
     * 확인한다. JS 러너 없이 오타를 잡는 최소 스모크다 (계획 §9).
     */
    @Test
    void console_정적_파일의_상대_import_경로는_모두_200이다() throws Exception {
        Path consoleRoot = resolveConsoleRoot();
        assertThat(consoleRoot).as("클래스패스의 static/console 디렉토리").isNotNull().isDirectory();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(consoleRoot)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".html") || name.endsWith(".js");
                    })
                    .toList();
        }

        assertThat(files).as("static/console 아래 .html/.js 파일").isNotEmpty();

        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String relative = consoleRoot.relativize(file).toString().replace('\\', '/');
            String basePath = "/console/" + relative;
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String spec : extractImportSpecifiers(content)) {
                if (spec.startsWith("http://") || spec.startsWith("https://")) {
                    continue;
                }
                String resolvedPath = resolve(basePath, spec);
                int status = mockMvc.perform(get(resolvedPath)).andReturn().getResponse().getStatus();
                if (status != 200) {
                    failures.add("%s -> '%s' (해석: %s) 응답 %d".formatted(basePath, spec, resolvedPath, status));
                }
            }
        }

        assertThat(failures).as("import 경로가 200이 아닌 항목들").isEmpty();
    }

    private static List<String> extractImportSpecifiers(String content) {
        List<String> specs = new ArrayList<>();
        addMatches(IMPORT_FROM, content, specs);
        addMatches(IMPORT_BARE, content, specs);

        Matcher scriptTags = SCRIPT_TAG.matcher(content);
        while (scriptTags.find()) {
            String attrs = scriptTags.group(1);
            if (!ATTR_TYPE_MODULE.matcher(attrs).find()) {
                continue;
            }
            Matcher srcMatcher = ATTR_SRC.matcher(attrs);
            if (srcMatcher.find()) {
                specs.add(srcMatcher.group(1));
            }
        }
        return specs;
    }

    private static void addMatches(Pattern pattern, String content, List<String> out) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    /** 콘솔 파일의 URL 경로 기준으로 상대 import 경로를 해석한다 (브라우저의 상대 URL 해석과 동일). */
    private static String resolve(String basePath, String spec) {
        URI base = URI.create("http://console.internal" + basePath);
        URI resolved = base.resolve(spec);
        return resolved.getPath();
    }

    /** 테스트 클래스패스(= Gradle의 `build/resources/main`)에서 `static/console` 디렉토리를 찾는다. */
    private static Path resolveConsoleRoot() throws IOException {
        URL resource = ConsoleShellIntegrationTest.class.getResource("/static/console");
        if (resource == null) {
            return null;
        }
        // Gradle 테스트는 exploded build/resources/main을 쓴다. jar라면 스모크가 조용히 빠지지 않게 실패시킨다.
        assertThat(resource.getProtocol()).as("static/console 리소스 프로토콜").isEqualTo("file");
        try {
            return Paths.get(resource.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
