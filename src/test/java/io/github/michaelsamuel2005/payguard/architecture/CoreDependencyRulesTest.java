package io.github.michaelsamuel2005.payguard.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The domain and application layers are plain Java: no Spring, no Resilience4j, no Micrometer, no Jakarta. The
 * domain does not even know the application layer or the database exists. Checked on the source files, so the
 * rule holds even for code that is never run.
 */
class CoreDependencyRulesTest {

    private static final Path MAIN = Path.of("src/main/java/io/github/michaelsamuel2005/payguard");
    private static final List<String> FRAMEWORKS =
            List.of("org.springframework", "io.github.resilience4j", "io.micrometer", "jakarta.", "javax.servlet");

    private static final Map<String, List<String>> FORBIDDEN = Map.of(
            "domain", concat(FRAMEWORKS, List.of("io.github.michaelsamuel2005.payguard.application",
                    "io.github.michaelsamuel2005.payguard.adapters", "io.github.michaelsamuel2005.payguard.web",
                    "java.sql", "javax.sql")),
            "application", concat(FRAMEWORKS, List.of("io.github.michaelsamuel2005.payguard.adapters",
                    "io.github.michaelsamuel2005.payguard.web", "java.sql", "javax.sql")),
            "adapters/jdbc", concat(FRAMEWORKS, List.of("io.github.michaelsamuel2005.payguard.web")),
            "adapters/directory", concat(FRAMEWORKS, List.of("io.github.michaelsamuel2005.payguard.web")));

    @Test
    void the_core_depends_on_nothing_but_the_jdk() throws IOException {
        int checked = 0;
        for (Map.Entry<String, List<String>> rule : FORBIDDEN.entrySet()) {
            try (Stream<Path> files = Files.walk(MAIN.resolve(rule.getKey()))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    checked++;
                    for (String line : Files.readAllLines(file)) {
                        if (line.startsWith("import ")) {
                            for (String banned : rule.getValue()) {
                                assertThat(line).as("%s must not import %s", file, banned)
                                        .doesNotContain(" " + banned).doesNotContain("static " + banned);
                            }
                        }
                    }
                }
            }
        }
        assertThat(checked).as("source files checked").isGreaterThan(40);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }
}
