#!/usr/bin/env python3
"""DB 접속 없이 스키마 매핑·migration 보존·테스트 분리·기본값 대응을 검사한다."""
from __future__ import annotations
import argparse
import hashlib
import re
from pathlib import Path

MIGRATION_HASHES = {
    "V001__create_learner_and_settings.sql": "fa2503e6939faec9e0a68447878efdba1a103d2d8e7ea10ea5738196d99a9d80",
    "V002__seed_default_settings.sql": "05c501277730ca56c562ae429fb6cd947eaa491c505f2756eafc51c7b032c5fd",
}

def verify(root: Path, be_root: Path | None) -> None:
    main = root / "src/main/kotlin/jp/co/translacat/languagelearning"
    resources = root / "src/main/resources/db/migration"
    for name, expected in MIGRATION_HASHES.items():
        # SQL은 수정하지 않는다. Windows checkout의 CRLF만 LF로 정규화하여 원본 내용을 비교한다.
        actual = hashlib.sha256((resources / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        assert actual == expected, f"적용된 migration이 달라졌습니다: {name}"
    print("PASS: V001/V002 content hashes preserved (LF-normalized; files not modified)")
    sql = (resources / "V001__create_learner_and_settings.sql").read_text(encoding="utf-8")
    classes = {
        "language_learning_learner": main / "features/learner/infrastructure/persistence/table/LearnersTable.kt",
        "language_learning_user_setting": main / "features/settings/infrastructure/persistence/table/UserSettingsTable.kt",
        "language_learning_admin_setting": main / "features/settings/infrastructure/persistence/table/AdminSettingsTable.kt",
        "language_learning_listening_policy_setting": main / "features/settings/infrastructure/persistence/table/ListeningPoliciesTable.kt",
    }
    total = 0
    conversions = {"BIGINT": "long", "INT": "integer", "BOOLEAN": "bool", "DOUBLE": "double", "DATETIME(6)": "datetime", "DATE": "date"}
    for table, path in classes.items():
        block = re.search(r"CREATE TABLE " + table + r" \((.*?)\) ENGINE=", sql, re.S).group(1)
        expected = {}
        for match in re.finditer(r"(?m)^    (\w+) (BIGINT|INT|DOUBLE|BOOLEAN|VARCHAR\(\d+\)|DATETIME\(6\)|DATE) (.*)", block):
            name, kind, constraints = match.groups()
            expected[name] = (kind, "NOT NULL" not in constraints)
        code = path.read_text(encoding="utf-8")
        actual = {}
        declarations = list(re.finditer(r"(?m)^    val \w+ = ", code))
        for index, match in enumerate(declarations):
            end = declarations[index + 1].start() if index + 1 < len(declarations) else code.index("    override val primaryKey")
            expr = code[match.end():end]
            match_expr = re.match(r'(\w+)\("(\w+)"(?:, (\d+))?\)', expr)
            assert match_expr, expr
            method, name, length = match_expr.groups()
            actual[name] = (method, length, ".nullable()" in expr)
        assert set(actual) == set(expected), f"{table}: 컬럼 목록 불일치"
        for name, (kind, nullable) in expected.items():
            method, length, got_nullable = actual[name]
            expected_method = "varchar" if kind.startswith("VARCHAR") else conversions[kind]
            assert method == expected_method and nullable == got_nullable, f"{table}.{name}: 타입/null 허용 불일치"
            if kind.startswith("VARCHAR"):
                assert int(length) == int(re.search(r"\d+", kind).group()), f"{table}.{name}: 길이 불일치"
        total += len(actual)
        print(f"PASS: {table}: {len(actual)} columns, types, nullability, VARCHAR lengths")
    print(f"MAPPED_COLUMNS={total}")
    user_table = classes["language_learning_user_setting"].read_text(encoding="utf-8")
    assert '.uniqueIndex("uk_language_learning_user_setting_user")' in user_table
    assert 'fkName = "fk_ll_user_setting_learner"' in user_table
    assert 'LearnersTable.userId' in user_table and user_table.count('ReferenceOption.RESTRICT') == 2
    print("PASS: same-LL FK and one-user-one-setting mapping declared")
    runner = (main / "shared/persistence/transaction/JdbcTransactionRunner.kt").read_text(encoding="utf-8")
    assert "transaction(db = database, readOnly = false)" in runner
    assert "maxAttempts = 1" in runner and "limitedParallelism(maximumConcurrency)" in runner
    assert "context.ensureActive()" in runner
    work = (main / "features/settings/application/GetOrCreateUserSettings.kt").read_text(encoding="utf-8")
    assert work.index("learners.ensureAndLock") < work.index("userSettings.findForUser") < work.index("policies.loadInitialPolicy")
    print("PASS: explicit DB transaction and learner-first operation order")
    new_sources = list((main / "features").rglob("*.kt"))
    for path in new_sources:
        text = path.read_text(encoding="utf-8")
        assert "SchemaUtils" not in text and "R2dbcDatabase" not in text
        assert "routing {" not in text, "인증 없는 새 HTTP route를 추가하면 안 됩니다."
    build = (root / "build.gradle.kts").read_text(encoding="utf-8")
    pattern = '"**/SettingsPersistenceIntegrationTest*"'
    assert pattern in re.search(r"tasks.test \{(.*?)\n\}", build, re.S).group(1)
    assert pattern in build[build.index('tasks.register<Test>("databaseIntegrationTest")'):]
    print("PASS: Settings MySQL tests excluded from regular test and included in databaseIntegrationTest")
    if be_root is not None:
        entity = (be_root / "src/main/java/jp/co/translacat/domain/languagelearning/setting/entity/LanguageLearningUserSetting.java").read_text(encoding="utf-8")
        kt = (main / "features/settings/domain/model/NewUserSettings.kt").read_text(encoding="utf-8")
        for literal in ('"Asia/Tokyo"', '"marin"', '"NORMAL"', '"[\\"DICTATION\\"]"'):
            assert literal in entity and literal in kt, f"원본 BE 기본값과 불일치: {literal}"
        service = (be_root / "src/main/java/jp/co/translacat/domain/languagelearning/setting/service/LanguageLearningUserSettingQueryService.java").read_text(encoding="utf-8")
        for method in ("getDefaultDailySentenceCount()", "getDefaultDailySpeakingGoalMinutes()", "getDefaultItemCount()"):
            assert method in service
        print("PASS: BE static initial defaults and source-of-goal policies compared")
    print("STATIC_CHECKS_PASSED (not a Gradle build or MySQL execution)")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--be-root", type=Path)
    args = parser.parse_args()
    verify(args.project_root, args.be_root)
