package com.anadepot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Source-level guards for the architecture rules (docs/architecture.md). They fail the build if a rule is broken. */
class ArchitectureTest {

	private static final Path MAIN = Path.of("src/main/java/com/anadepot");
	private static final Path TEST = Path.of("src/test/java");

	private static List<Path> javaFiles(Path root) throws IOException {
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}

	private static List<String> importsOf(Path file) throws IOException {
		return Files.readAllLines(file).stream().map(String::strip).filter(l -> l.startsWith("import ")).toList();
	}

	@Test
	void onlyCoreAndInfraExistAsTopLevelPackages() throws IOException {
		try (Stream<Path> children = Files.list(MAIN)) {
			assertThat(children.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("core", "infra");
		}
	}

	@Test
	void coreIsFreeOfSpringJpaWebAndProviderDependencies() throws IOException {
		var forbidden = List.of("org.springframework", "jakarta.persistence", "jakarta.servlet", "jakarta.validation", "org.hibernate", "tools.jackson",
				"com.fasterxml", "com.nimbusds", "io.micrometer", "org.slf4j", "org.postgresql", "java.sql", "javax.sql", "com.anadepot.infra");
		for (var file : javaFiles(MAIN.resolve("core"))) {
			for (var line : importsOf(file)) {
				for (var f : forbidden) {
					assertThat(line).as(file + " must not depend on " + f).doesNotContain("import " + f).doesNotContain("import static " + f);
				}
			}
		}
	}

	@Test
	void jpaEntitiesLiveInInfraNeverInCore() throws IOException {
		for (var file : javaFiles(MAIN.resolve("core"))) {
			assertThat(Files.readString(file)).as(file + " must not declare JPA entities").doesNotContain("@Entity").doesNotContain("@Table");
		}
	}

	@Test
	void coreServicesFollowTheServiceAndServiceImplConvention() throws IOException {
		try (Stream<Path> dirs = Files.walk(MAIN.resolve("core"), 3)) {
			var serviceDirs = dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
			assertThat(serviceDirs).contains("service", "serviceImpl");
		}
		for (var file : javaFiles(MAIN.resolve("core"))) {
			if (file.toString().contains("/serviceImpl/")) {
				assertThat(file.getFileName().toString()).endsWith("Impl.java");
			}
		}
	}

	@Test
	void noRawJdbcOrJdbcTemplateAnywhere_includingTests() throws IOException {
		var banned = List.of("JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcClient", "java.sql.", "DriverManager", "ResultSet", "PreparedStatement", "SimpleJdbcInsert");
		for (var root : List.of(MAIN, TEST)) {
			for (var file : javaFiles(root)) {
				if (file.getFileName().toString().equals("ArchitectureTest.java")) {
					continue;
				}
				var text = Files.readString(file);
				for (var b : banned) {
					assertThat(text).as(file + " must not use " + b).doesNotContain(b);
				}
			}
		}
	}

	@Test
	void noFloatingPointMoneyInTheDomain() throws IOException {
		for (var file : javaFiles(MAIN.resolve("core"))) {
			var text = Files.readString(file);
			assertThat(text).as(file + " must not use float/double for money").doesNotContainPattern("\\b(double|float|Double|Float)\\s+\\w*([Pp]rice|[Aa]mount|[Tt]otal|[Mm]oney)");
		}
	}
}
