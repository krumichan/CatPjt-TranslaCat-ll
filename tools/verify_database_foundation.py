#!/usr/bin/env python3
"""Offline source/SQL contract checks, NOT a MySQL execution or Kotlin build.

Python 3.11+. Optional --be-root checks the fixture against the actual BE archive sources.
No database access and no file modifications.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
import re
import tomllib
from pathlib import Path


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def snake(value: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", value).lower()


def arithmetic(expression: str) -> int | float:
    def visit(node: ast.AST) -> int | float:
        if isinstance(node, ast.Constant) and type(node.value) in (int, float):
            return node.value
        if isinstance(node, ast.BinOp):
            a, b = visit(node.left), visit(node.right)
            if isinstance(node.op, ast.Mult):
                return a * b
            if isinstance(node.op, ast.Add):
                return a + b
        raise ValueError("Unsupported Java constant expression")
    return visit(ast.parse(expression.replace("L", ""), mode="eval").body)


def java_value(expression: str, constants: dict) -> object:
    expression = expression.strip()
    if expression in constants:
        return constants[expression]
    if expression in ("true", "false"):
        return expression == "true"
    if expression.startswith('"'):
        return json.loads(expression)
    return arithmetic(expression)


def split_items(text: str) -> list[str]:
    result, buffer = [], []
    depth, quoted, index = 0, False, 0
    while index < len(text):
        char = text[index]
        if quoted:
            buffer.append(char)
            if char == "'":
                if index + 1 < len(text) and text[index + 1] == "'":
                    buffer.append("'")
                    index += 1
                else:
                    quoted = False
        elif char == "'":
            quoted = True
            buffer.append(char)
        elif char == "(":
            depth += 1
            buffer.append(char)
        elif char == ")":
            depth -= 1
            check(depth >= 0, "Unbalanced SQL parentheses")
            buffer.append(char)
        elif char == "," and depth == 0:
            result.append("".join(buffer).strip())
            buffer = []
        else:
            buffer.append(char)
        index += 1
    check(not quoted and depth == 0, "Unbalanced SQL quotes or parentheses")
    result.append("".join(buffer).strip())
    return result


def sql_value(value: str) -> object:
    if value in ("TRUE", "FALSE"):
        return value == "TRUE"
    if value == "NULL":
        return None
    if value.startswith("'") and value.endswith("'"):
        return value[1:-1].replace("''", "'")
    if value == "UTC_TIMESTAMP(6)":
        return value
    return float(value) if "." in value else int(value)


def verify_source(be_root: Path, table: str, spec: dict) -> None:
    path = be_root / spec["source"]
    contents = path.read_bytes()
    check(hashlib.sha256(contents.replace(b"\r\n", b"\n")).hexdigest() == spec["normalized_sha256"],
          f"BE source version changed: {path}")
    text = contents.decode("utf-8")
    class_name = path.stem
    prefix = text.split("    private " + class_name + "(")[0]
    constants = {}
    for name, expression in re.findall(r"(?:public|private) static final [\w<>]+ (\w+)\s*=\s*([^;]+);", prefix):
        constants[name] = java_value(expression, constants)
    actual = []
    for annotations, field_type, field in re.findall(r"((?:\s*@[^\n]+\n)*)\s*private (\w+) (\w+);", prefix):
        if field_type == "User":
            column, db_type, nullable = "user_id", "BIGINT", False
        else:
            explicit = re.search(r'@Column\([^)]*name\s*=\s*"([^"]+)"', annotations)
            column = explicit.group(1) if explicit else snake(field)
            length = re.search(r"length\s*=\s*(\d+)", annotations)
            db_type = {
                "String": f"VARCHAR({length.group(1) if length else 255})",
                "int": "INT", "Integer": "INT", "Long": "BIGINT", "long": "BIGINT",
                "boolean": "BOOLEAN", "Boolean": "BOOLEAN", "double": "DOUBLE", "LocalDate": "DATE",
            }[field_type]
            nullable = not ("nullable = false" in annotations or "@Id" in annotations)
        actual.append((field, column, db_type, nullable))
    expected = [(f["field"], f["column"], f["type"], f["nullable"]) for f in spec["columns"]]
    check(actual == expected, f"Mapped column contract differs: {table}")
    if spec["seed"]:
        constructor = text.split("    private " + class_name + "(")[1].split("\n    }", 1)[0]
        values = {}
        by_field = {f["field"]: f["column"] for f in spec["columns"]}
        for field, expression in re.findall(r"this\.(\w+)\s*=\s*([^;]+);", constructor):
            values[by_field[field]] = constants["DEFAULT_ID"] if expression.strip() == "id" else java_value(expression, constants)
        check(values == spec["seed"], f"Constructor seed contract differs: {table}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--be-root", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    baseline = json.loads((root / "src/test/resources/db/be-settings-baseline.json").read_text(encoding="utf-8"))
    migration_root = root / "src/main/resources/db/migration"
    paths = sorted(migration_root.glob("*.sql"))
    check([p.name for p in paths] == ["V001__create_learner_and_settings.sql", "V002__seed_default_settings.sql", "V003__create_admin_settings_audit.sql", "V004__create_settings_selection_delivery.sql", "V005__create_keyword_catalog.sql", "V006__create_learning_result_journal.sql", "V007__create_level_test.sql"],
          "Expected V001/V002 foundation, V003 audit V004 selection delivery and V005 keyword catalog")
    strip_comments = lambda s: "\n".join(line for line in s.splitlines() if not line.lstrip().startswith("--"))
    schema, seed = (strip_comments(p.read_text(encoding="utf-8")) for p in paths[:2])
    check(not re.search(r"\b(DROP|TRUNCATE|REPLACE|USE)\b|DELETE\s+FROM|CREATE\s+DATABASE", schema + seed, re.I),
          "Unexpected destructive or database-selection statement in migrations")
    check("IF NOT EXISTS" not in schema, "Do not hide existing incompatible tables")
    check("translacat." not in schema + seed, "Cross-database reference found")
    creates = re.findall(r"CREATE TABLE (\w+)\s*\((.*?)\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;", schema, re.S)
    check({name for name, _ in creates} == set(baseline) | {"language_learning_learner"}, "Wrong table boundary")
    check(len(creates) == 4, "Expected four CREATE TABLE statements")
    mapped_count = 0
    for table, body in creates:
        parts = split_items(body)
        if table not in baseline:
            check("PRIMARY KEY (user_id)" in parts, "Learner identity must be owned by LL")
            continue
        columns = {}
        for part in parts:
            found = re.fullmatch(r"(\w+) (VARCHAR\(\d+\)|DATETIME\(6\)|BIGINT|INT|DOUBLE|BOOLEAN|DATE) (NOT NULL|NULL)( AUTO_INCREMENT)?", part)
            if found:
                name, typ, null, auto = found.groups()
                columns[name] = {"type": typ, "nullable": null == "NULL", "auto_increment": bool(auto)}
        expected = baseline[table]["columns"]
        check(len(columns) == len(expected) + 4, f"Column count (including audit) differs: {table}")
        for field in expected:
            actual = columns.get(field["column"])
            check(actual == {k: field[k] for k in ("type", "nullable", "auto_increment")}, f"DDL drift: {table}.{field['column']}")
            mapped_count += 1
        for name in ("created_at", "updated_at"):
            check(columns[name] == {"type": "DATETIME(6)", "nullable": False, "auto_increment": False}, "Audit datetime mismatch")
        check("PRIMARY KEY (id)" in parts, f"Missing primary key: {table}")
    check(schema.count("FOREIGN KEY") == 1 and "REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT" in schema,
          "User settings must reference only the LL learner, without cascades")
    check("uk_language_learning_user_setting_user UNIQUE (user_id)" in schema, "Missing one-setting-per-user constraint")
    inserts = re.findall(r"INSERT INTO (\w+)\s*\((.*?)\) VALUES\s*\((.*?)\);", seed, re.S)
    check(len(inserts) == 2, "Expected exactly two singleton inserts")
    seed_count = 0
    for table, names, values in inserts:
        names, values = split_items(names), split_items(values)
        check(len(names) == len(values), "Mismatched seed columns and values")
        row = dict(zip(names, map(sql_value, values)))
        expected_seed = baseline[table]["seed"]
        check({k: row[k] for k in expected_seed} == expected_seed, f"SQL seed differs from BE: {table}")
        check(row["created_by"] == row["updated_by"] == "SYSTEM", "Wrong initial audit identity")
        check(row["created_at"] == row["updated_at"] == "UTC_TIMESTAMP(6)", "Wrong initial audit time")
        seed_count += len(expected_seed)
    check(not re.search(r"\bUPDATE\b|ON DUPLICATE", seed, re.I), "Seed must not reset modified settings")
    versions = tomllib.loads((root / "gradle/libs.versions.toml").read_text())
    check(versions["versions"]["flyway"] == "13.7.0", "Unreviewed Flyway version")
    for lib in ("flyway-core", "flyway-mysql"):
        check(versions["libraries"][lib]["version"]["ref"] == "flyway", "Flyway module versions must match")
    check("config/application-local.yaml" in (root / ".gitignore").read_text(), "Local secrets must remain ignored")
    print(f"PASS: 4 LL tables; {mapped_count} BE entity columns plus 12 audit columns; learner is new, not copied from Core.")
    print(f"PASS: {seed_count} explicit seed values (including the two DEFAULT IDs) match the baseline.")
    print("PASS: SQL boundary, foreign key, unique key, non-overwriting seed, version-catalog and gitignore checks.")
    if args.be_root:
        for table, spec in baseline.items():
            verify_source(args.be_root, table, spec)
        print("PASS: original BE file hashes, entity mappings and Java constructor values independently rechecked.")
    else:
        print("NOTE: original BE source comparison not requested; used the checked-in baseline fixture.")
    print("NOT RUN by this script: MySQL SQL execution, Gradle/Ktor build, or server startup.")


if __name__ == "__main__":
    main()
