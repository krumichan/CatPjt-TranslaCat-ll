package jp.co.translacat.languagelearning.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 추가 라이브러리 없이 소스의 package/import 경계를 검사한다.
 * 완전 수식 호출·리플렉션까지 분석하는 컴파일러/바이트코드 의존성 검사는 아니다.
 * Gradle test 또는 프로젝트 안의 작업 디렉토리에서 실행한다.
 */
class SourceArchitectureTest {
    @Test
    fun `소스와 테스트의 패키지는 디렉토리와 일치한다`() {
        val project = projectRoot()
        for (relativeRoot in listOf("src/main/kotlin", "src/test/kotlin")) {
            val root = project.resolve(relativeRoot)
            val sources = readSources(root)
            assertTrue(sources.isNotEmpty(), "소스 파일을 찾을 수 없습니다: $root")
            for (source in sources) {
                val expected = root.relativize(source.path.parent).joinToString(".")
                assertEquals(expected, source.packageName, "패키지/경로 불일치: ${source.path}")
            }
        }
    }

    @Test
    fun `도메인은 전송 계층과 영속성 구현에 의존하지 않는다`() {
        val sources = productionSources().filter { ".domain." in "${it.packageName}." }
        assertTrue(sources.isNotEmpty(), "도메인 소스가 없어 검사를 수행할 수 없습니다.")
        assertNoImports(sources) { target ->
            isFrameworkOrJdbc(target) ||
                (target.startsWith("$ROOT.") &&
                    ".domain." !in target &&
                    !target.startsWith("$ROOT.shared.error.") &&
                    !target.startsWith("$ROOT.shared.time."))
        }
    }

    @Test
    fun `애플리케이션은 Ktor Exposed와 저장소 구현에 의존하지 않는다`() {
        val sources = productionSources().filter { ".application." in "${it.packageName}." }
        assertTrue(sources.isNotEmpty(), "애플리케이션 소스가 없어 검사를 수행할 수 없습니다.")
        assertNoImports(sources) { target ->
            isFrameworkOrJdbc(target) ||
                (target.startsWith("$ROOT.") && (
                    ".infrastructure." in target ||
                        ".api." in target ||
                        target.startsWith("$ROOT.bootstrap.") ||
                        target.startsWith("$ROOT.shared.persistence.") ||
                        target.startsWith("$ROOT.shared.http.")
                    ))
        }
    }

    @Test
    fun `공통 영속성은 Ktor와 특정 기능의 코드를 참조하지 않는다`() {
        val sources = productionSources().filter { it.packageName.startsWith("$ROOT.shared.persistence") }
        assertTrue(sources.isNotEmpty(), "공통 영속성 소스가 없어 검사를 수행할 수 없습니다.")
        assertNoImports(sources) { target ->
            target.startsWith("io.ktor.") ||
                target.startsWith("$ROOT.features.") ||
                target.startsWith("$ROOT.bootstrap.")
        }
    }

    @Test
    fun `다른 기능의 영속성 구현 참조는 의도한 연결만 허용한다`() {
        // 같은 LL DB 안의 learner FK와 UnitOfWork 조립만 명시적으로 허용한다.
        val allowed = setOf(
            "$ROOT.features.keyword.infrastructure.persistence.table.CustomKeywordsTable" to
                "$ROOT.features.learner.infrastructure.persistence.table.LearnersTable",
            "$ROOT.features.keyword.infrastructure.persistence.table.SystemKeywordSelectionsTable" to
                "$ROOT.features.learner.infrastructure.persistence.table.LearnersTable",
            "$ROOT.features.keyword.infrastructure.persistence.ExposedKeywordUnitOfWork" to
                "$ROOT.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository",
            "$ROOT.features.settings.infrastructure.persistence.table.SettingsSelectionDeliveriesTable" to
                "$ROOT.features.learner.infrastructure.persistence.table.LearnersTable",
            "$ROOT.features.settings.infrastructure.persistence.table.UserSettingsTable" to
                "$ROOT.features.learner.infrastructure.persistence.table.LearnersTable",
            "$ROOT.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork" to
                "$ROOT.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository",
        )
        val violations = mutableListOf<String>()
        for (source in productionSources()) {
            val owner = featureOf(source.packageName) ?: continue
            for (target in source.imports) {
                val other = featureOf(target) ?: continue
                if (owner != other && ".infrastructure." in target && (source.primaryName to target) !in allowed) {
                    violations += "${source.primaryName} -> $target"
                }
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `Table과 저장소 계약 및 구현은 각 책임의 패키지에 둔다`() {
        for (source in productionSources()) {
            if (featureOf(source.packageName) == null) continue
            val name = source.path.fileName.toString().removeSuffix(".kt")
            when {
                name.endsWith("Table") || name.endsWith("Tables") ->
                    assertTrue(source.packageName.endsWith(".infrastructure.persistence.table"), source.primaryName)

                name.startsWith("Exposed") && name.endsWith("Repository") ->
                    assertTrue(
                        source.packageName.endsWith(".infrastructure.persistence.repository"),
                        source.primaryName
                    )

                name.endsWith("Repository") ->
                    assertTrue(source.packageName.endsWith(".domain.repository"), source.primaryName)
            }
        }
    }

    private fun productionSources(): List<SourceFile> {
        val files = readSources(projectRoot().resolve("src/main/kotlin"))
        check(files.isNotEmpty()) { "검사할 main Kotlin 소스가 없습니다." }
        return files
    }

    private fun projectRoot(): Path = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.isDirectory(it.resolve("src/main/kotlin")) }
        ?: error("프로젝트 루트(src/main/kotlin)를 찾을 수 없습니다. 작업 디렉토리를 확인해 주세요.")

    private fun readSources(root: Path): List<SourceFile> {
        check(Files.isDirectory(root)) { "소스 디렉토리가 없습니다: $root" }
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .sorted()
                .map { path ->
                    val text = Files.readString(path).replace("\r\n", "\n")
                    val packageName = Regex("(?m)^package\\s+([\\w.]+)\\s*$")
                        .find(text)?.groupValues?.get(1)
                        ?: error("package 선언이 없습니다: $path")
                    val imports = Regex("(?m)^import\\s+([\\w.*]+)(?:\\s+as\\s+\\w+)?\\s*$")
                        .findAll(text).map { it.groupValues[1] }.toList()
                    SourceFile(path, packageName, imports)
                }
                .toList()
        }
    }

    private fun assertNoImports(sources: List<SourceFile>, forbidden: (String) -> Boolean) {
        val violations = sources.flatMap { source ->
            source.imports.filter(forbidden).map { "${source.primaryName} -> $it" }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    private fun isFrameworkOrJdbc(target: String): Boolean = listOf(
        "io.ktor.", "org.jetbrains.exposed.", "org.flywaydb.", "com.zaxxer.hikari.",
        "java.sql.", "javax.sql.",
    ).any { target.startsWith(it) }

    private fun featureOf(name: String): String? = name.takeIf { it.startsWith("$ROOT.features.") }
        ?.removePrefix("$ROOT.features.")?.substringBefore('.')

    private data class SourceFile(
        val path: Path,
        val packageName: String,
        val imports: List<String>,
    ) {
        val primaryName: String
            get() = "$packageName.${path.fileName.toString().removeSuffix(".kt")}"
    }

    private companion object {
        const val ROOT = "jp.co.translacat.languagelearning"
    }
}
