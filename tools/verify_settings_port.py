#!/usr/bin/env python3
"""Settings 이식의 파일·DTO·보안 연결을 정적으로 검사한다. Gradle/MySQL 검증을 대체하지 않는다."""
from __future__ import annotations

import argparse
import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "src/main/kotlin/jp/co/translacat/languagelearning"
DTO_COUNTS = {
    "UserSettingUpdateRequestDto": 9,
    "UserSettingResponseDto": 23,
    "AdminSettingUpdateRequestDto": 30,
    "AdminSettingResponseDto": 30,
}


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


def fields(path: Path) -> list[tuple[str, str]]:
    return re.findall(r"\bval\s+(\w+)\s*:\s*([\w<>?]+)", path.read_text(encoding="utf-8"))


def verify(be_root: Path | None) -> None:
    for name, count in DTO_COUNTS.items():
        found = fields(MAIN / f"features/settings/api/dto/{name}.kt")
        require(len(found) == count, f"{name}: 필드 수가 달라졌습니다.")
        require(len({name for name, _ in found}) == count, f"{name}: 중복 필드가 있습니다.")
        if be_root:
            kind = "request" if "Request" in name else "response"
            original = (be_root / f"src/main/java/jp/co/translacat/domain/languagelearning/setting/dto/{kind}/{name}.java").read_text(encoding="utf-8")
            record = original.split("record " + name, 1)[1].split(")", 1)[0]
            be_names = re.findall(r"\b(?:String|Integer|int|Long|long|Double|double|Boolean|boolean|LocalDate|List<[^>]+>)\s+(\w+)", record)
            require([name for name, _ in found] == be_names, f"{name}: 원본 DTO 필드 순서/이름 불일치")
        print(f"PASS: {name}: {count} fields")
    migration = ROOT / "src/main/resources/db/migration"
    names = sorted(p.name for p in migration.glob("*.sql"))
    require(names == ["V001__create_learner_and_settings.sql", "V002__seed_default_settings.sql", "V003__create_admin_settings_audit.sql"], "migration 목록 불일치")
    audit = (migration / names[2]).read_text(encoding="utf-8")
    sql = "\n".join(line for line in audit.splitlines() if not line.strip().startswith("--"))
    require(re.findall(r"CREATE TABLE (\w+)", sql) == ["language_learning_admin_setting_audit"], "V003 범위 불일치")
    require(not re.search(r"\b(DROP|ALTER|UPDATE|DELETE|TRUNCATE|INSERT|REFERENCES)\b", sql, re.I), "V003에서 기존 스키마/데이터/외부 FK를 변경하면 안 됩니다.")
    print("PASS: V003 only creates audit table; no data updates or Core foreign keys")
    routes = (MAIN / "features/settings/api/SettingsRoutes.kt").read_text(encoding="utf-8")
    require("authenticate(INTERNAL_AUTH)" in routes, "내부 인증 누락")
    require(routes.count('get("/internal/v1/') == 2 and routes.count('patch("/internal/v1/') == 2, "내부 경로 계약 불일치")
    require('get("/api/' not in routes and 'patch("/api/' not in routes, "외부 FE 경로를 우회 별칭으로 만들면 안 됩니다.")
    require("requireAdministrator()" in routes and "call.verifiedUser()" in routes, "사용자/권한 검증 누락")
    bootstrap = (MAIN / "bootstrap/InternalAuthentication.kt").read_text(encoding="utf-8")
    for fragment in ["Algorithm.HMAC256", ".withIssuer(", ".withAudience(", '.withClaim("service"', '.withClaim("tokenUse"', "InternalClaimsPolicy.validate"]:
        require(fragment in bootstrap, "내부 JWT 검증 항목 누락: " + fragment)
    require("configureSettingsHttp()" in (MAIN.parent / "languagelearning/Application.kt").read_text(encoding="utf-8"), "HTTP bootstrap 누락")
    print("PASS: four authenticated internal routes and fixed-algorithm JWT boundary")
    build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    require(build.count('"**/SettingsFeatureIntegrationTest*"') == 2, "신규 MySQL 테스트가 일반 테스트에서 제외되고 통합 작업에 포함되어야 합니다.")
    print("PASS: explicit MySQL test separation")
    print("STATIC_CHECKS_PASSED (no Gradle/Ktor/MySQL runtime validation)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--be-root", type=Path)
    verify(parser.parse_args().be_root)
