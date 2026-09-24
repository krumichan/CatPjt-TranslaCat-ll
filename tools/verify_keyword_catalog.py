#!/usr/bin/env python3
"""키워드 V005 범위, Exposed 컬럼, DTO 이름, 인증/테스트 경계를 정적으로 대조한다. DB/Gradle 실행 검사는 아니다."""
from __future__ import annotations
import argparse
import re
from pathlib import Path


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def verify(root: Path, be_root: Path | None) -> None:
    main = root / "src/main/kotlin/jp/co/translacat/languagelearning"
    feature = main / "features/keyword"
    sql = (root / "src/main/resources/db/migration/V005__create_keyword_catalog.sql").read_text(encoding="utf-8")
    sql = "\n".join(line for line in sql.splitlines() if not line.lstrip().startswith("--"))
    mapping = {
        "language_learning_system_keyword": "SystemKeywordsTable.kt",
        "language_learning_system_keyword_locale": "SystemKeywordLocalesTable.kt",
        "language_learning_custom_keyword": "CustomKeywordsTable.kt",
        "language_learning_user_system_keyword": "SystemKeywordSelectionsTable.kt",
        "language_learning_keyword_catalog_lock": "KeywordCatalogLockTable.kt",
    }
    check(set(re.findall(r"CREATE TABLE (\w+)", sql)) == set(mapping), "V005 테이블 범위 불일치")
    check(re.findall(r"INSERT INTO (\w+)", sql) == ["language_learning_keyword_catalog_lock"], "예제/사용자 데이터가 seed에 들어갔습니다.")
    check(not re.search(r"\b(DROP|ALTER|TRUNCATE|REPLACE)\b|DELETE\s+FROM|UPDATE\s+\w+\s+SET", sql, re.I), "기존 데이터/스키마 변경문이 있습니다.")
    check("translacat." not in sql and "REFERENCES user" not in sql, "Core FK를 만들면 안 됩니다.")
    total = 0
    for table, name in mapping.items():
        body = sql.split("CREATE TABLE " + table + " (", 1)[1].split(") ENGINE=", 1)[0]
        expected = {m[0]: (m[1], m[2] == "NULL") for m in re.findall(r"(?m)^    (\w+) (BIGINT|INT|BOOLEAN|VARCHAR\(\d+\)|DATETIME\(6\)|DATE) (NOT NULL|NULL)", body)}
        code = (feature / "infrastructure/persistence/table" / name).read_text(encoding="utf-8")
        declarations = list(re.finditer(r"(?m)^    val \w+ = (long|integer|bool|varchar|datetime|date)\(\"(\w+)\"(?:, (\d+))?\)", code))
        actual = {}
        for i, m in enumerate(declarations):
            end = declarations[i + 1].start() if i + 1 < len(declarations) else code.index("    override val primaryKey")
            kind = {"long": "BIGINT", "integer": "INT", "bool": "BOOLEAN", "datetime": "DATETIME(6)", "date": "DATE"}.get(m[1], "VARCHAR(" + str(m[3]) + ")")
            actual[m[2]] = (kind, ".nullable()" in code[m.start():end])
        check(expected == actual, f"컬럼/타입/null 허용 불일치: {table}\n{expected}\n{actual}")
        total += len(actual)
    print(f"PASS: V005 five tables, lock-only seed, {total} mapped columns including lock, no Core FK/data changes")
    routes = (feature / "api/KeywordRoutes.kt").read_text(encoding="utf-8")
    auth = (main / "bootstrap/KeywordAuthentication.kt").read_text(encoding="utf-8")
    check("authenticate(KEYWORD_AUTH)" in routes and "call.administrator()" in routes, "인증/관리자 경계 누락")
    for fragment in ('.withClaim("tokenUse", "ll-keywords")', '"keywordLearningStarted"', "asBoolean()", "InternalClaimsPolicy.validate"):
        check(fragment in auth, f"서명된 사실/용도 검증 누락: {fragment}")
    check("KEYWORD_AUTH" in (main / "shared/security/KeywordPrincipal.kt").read_text(), "principal 누락")
    check('"**/KeywordCatalogIntegrationTest*"' in (root / "build.gradle.kts").read_text(), "통합 테스트 설정 누락")
    check((root / "build.gradle.kts").read_text().count('"**/KeywordCatalogIntegrationTest*"') == 2, "일반 테스트 제외/DB 테스트 포함을 같이 설정해야 합니다.")
    print("PASS: keyword JWT purpose + mandatory Boolean fact + separate MySQL task")
    if be_root:
        be = be_root / "src/main/java/jp/co/translacat"
        removed = ["SystemKeyword", "SystemKeywordLocale", "CustomKeyword", "UserSystemKeywordSelection",
                   "SystemKeywordRepository", "SystemKeywordLocaleRepository", "CustomKeywordRepository", "UserSystemKeywordSelectionRepository",
                   "LanguageLearningKeywordQueryService", "CustomKeywordCommandService", "SystemKeywordCommandService", "SystemKeywordSelectionCommandService",
                   "KeywordApplicationTimingPolicy", "KeywordValidationPolicy", "KeywordHierarchyPolicy", "SystemKeywordDisplayNameResolver", "KeywordResponseMapper"]
        rx = re.compile(r"\b(?:" + "|".join(removed) + r")\b")
        for p in be.rglob("*.java"):
            check(not rx.search(p.read_text(encoding="utf-8")), f"옛 키워드 구현 참조가 남았습니다: {p}")
        for name, expected_count in (("KeywordCreateRequestDto", 5), ("KeywordUpdateRequestDto", 6), ("KeywordResponseDto", 13), ("KeywordListResponseDto", 2)):
            old = next((be / "domain/languagelearning/keyword/dto").rglob(name + ".java")).read_text(encoding="utf-8")
            block = re.search(r"public record " + name + r"\s*\((.*?)\)\s*\{", old, re.S).group(1)
            names = re.findall(r"(?:\w+(?:<[^>]+>)?)\s+(\w+)\s*(?:,|$)", block)
            source = next(p.read_text(encoding="utf-8") for p in (feature / "api/dto").glob("*.kt") if "data class " + name + "(" in p.read_text(encoding="utf-8"))
            start = source.index("data class " + name + "(")
            fields = source[start:].split(")", 1)[0]
            kt_names = re.findall(r"\bval (\w+)\s*:", fields)
            check(names == kt_names and len(names) == expected_count, f"DTO 필드 이름/순서 불일치: {name}: {names}, {kt_names}")
        check((be / "domain/languagelearning/keyword/entity/KeywordMastery.java").exists(), "KeywordMastery는 아직 Core 평가 트랜잭션 소유입니다.")
        print("PASS: legacy main references zero; external DTO fields 5/6/13/2 preserved; Core mastery retained")
    print("STATIC_CHECKS_PASSED (not a Gradle compile, MySQL run, JWT library test or end-to-end test)")


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    args.add_argument("--be-root", type=Path)
    options = args.parse_args()
    verify(options.project_root, options.be_root)
