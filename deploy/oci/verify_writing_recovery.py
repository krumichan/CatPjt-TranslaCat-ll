#!/usr/bin/env python3
"""Offline admission and opt-in isolated schema recovery proof. Never adopts existing resources."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import time
import uuid
from pathlib import Path

OWNER = "translacat-writing-recovery-v1"
CAPABILITY = "io.translacat.ll.database-validation"
BASE_REVISION = "61224432a166c8582341a83d9c85ac090832eb37"
POLICY = "curated-writing-feedback-v14"
CONFIG_HASHES = {
    "src/main/resources/application.yaml": "20ed92b2e46ee5a6f9881b08b25fbaeef915361e1af3446f23cfb5a71a76b53f",
    "config/application-prod.yaml": "08b645ae807d31edb797e32c27e72fe2f7653f14735927c21ea2cab748ce97fe",
}
DISABLED_FLAGS = {"curatedEnabled", "feedbackEnabled", "qaOnly", "variableNEnabled"}
ARTIFACT_ROOT = Path(__file__).resolve().parents[3] / ".codex-workspace/verification/shared/writing-production-release-20261018/rollback-fix"
SHA256 = re.compile(r"[0-9a-f]{64}")
REVISION = re.compile(r"[0-9a-f]{40}")
PIN = re.compile(r"(?:sha256:[0-9a-f]{64}|[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64})")


class RecoveryError(Exception):
    pass


def need(condition, code):
    if not condition:
        raise RecoveryError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def confined(path, root):
    resolved = Path(path).resolve()
    need(resolved.is_relative_to(root.resolve()), "ARTIFACT_PATH_OUTSIDE_OWNED_ROOT")
    return resolved


def file_sha(path):
    return sha(path.read_bytes())


def image_spec(value, name, *, revision=True):
    keys = {"image", "expectedImageId", "expectedRevision", "runtimeOperatingMode"} if revision else {"image", "expectedImageId"}
    need(isinstance(value, dict) and set(value) == keys, "INVALID_IMAGE_FIELDS:" + name)
    need(isinstance(value["image"], str) and PIN.fullmatch(value["image"]), "IMMUTABLE_IMAGE_REQUIRED:" + name)
    need(re.fullmatch(r"sha256:[0-9a-f]{64}", value["expectedImageId"]) is not None, "IMAGE_ID_REQUIRED:" + name)
    if revision:
        need(REVISION.fullmatch(value["expectedRevision"]) is not None, "REVISION_REQUIRED:" + name)
        mode = value["runtimeOperatingMode"]
        need(isinstance(mode, dict) and set(mode) == DISABLED_FLAGS | {"mode"}
             and mode["mode"] == "CURATED_DISABLED"
             and all(mode[key] is False for key in DISABLED_FLAGS),
             "CURATED_DISABLED_OPERATING_MODE_REQUIRED:" + name)
    return value


def source_proof(path, expected_sha, root, expected_revision):
    # 복구 context의 전체 경로집합과 각 바이트를 묶는다. 선언하지 않은 파일도 거절한다.
    need(SHA256.fullmatch(expected_sha) is not None and file_sha(path) == expected_sha, "SOURCE_MANIFEST_HASH_MISMATCH")
    doc = json.loads(path.read_text(encoding="utf-8"))
    need(set(doc) == {"baseRevision", "revision", "sourceRoot", "exactPatchPaths", "files", "operatingConditions", "canonicalConfigSha256"}, "SOURCE_MANIFEST_FIELDS")
    need(doc["baseRevision"] == BASE_REVISION and doc["revision"] == expected_revision, "SOURCE_REVISION_MISMATCH")
    conditions = doc["operatingConditions"]
    need(isinstance(conditions, dict) and set(conditions) == DISABLED_FLAGS
         and all(conditions[key] is False for key in DISABLED_FLAGS),
         "SOURCE_OPERATING_CONDITIONS_REQUIRED")
    need(doc["canonicalConfigSha256"] == CONFIG_HASHES, "CANONICAL_CONFIG_PINS_MISMATCH")
    source_root = confined(doc["sourceRoot"], root)
    need(source_root.is_dir(), "SOURCE_ROOT_MISSING")
    files = doc["files"]
    need(isinstance(files, list) and files, "EMPTY_SOURCE_MANIFEST")
    seen = set()
    for row in files:
        need(isinstance(row, dict) and set(row) == {"path", "sha256"}, "SOURCE_FILE_FIELDS")
        rel = row["path"]
        need(isinstance(rel, str) and rel and "\\" not in rel and not Path(rel).is_absolute(), "INVALID_SOURCE_PATH")
        current = confined(source_root / rel, source_root)
        need(not current.is_symlink() and rel not in seen, "DUPLICATE_OR_LINK_SOURCE")
        need(current.is_file() and SHA256.fullmatch(row["sha256"]) and file_sha(current) == row["sha256"], "SOURCE_FILE_HASH_MISMATCH")
        seen.add(rel)
    actual = set()
    for current in source_root.rglob("*"):
        need(not current.is_symlink(), "SOURCE_LINK_FORBIDDEN")
        if current.is_file():
            actual.add(current.relative_to(source_root).as_posix())
    need(actual == seen, "SOURCE_PATH_SET_MISMATCH")
    # 공개 설정 pin만 CRLF를 LF로 정규화한다. 위 파일 manifest는 원본 바이트 SHA를 그대로 검사한다.
    for rel, expected in CONFIG_HASHES.items():
        need(rel in seen and sha((source_root / rel).read_bytes().replace(b"\r\n", b"\n")) == expected, "CANONICAL_CONFIG_BYTES_MISMATCH")
    patches = doc["exactPatchPaths"]
    need(isinstance(patches, list) and patches and len(set(patches)) == len(patches) and set(patches) <= seen, "PATCH_PATH_SET_INVALID")
    return {"sha256": expected_sha, "baseRevision": BASE_REVISION, "revision": expected_revision,
            "fileCount": len(seen), "exactPatchPaths": patches, "operatingConditions": conditions, "canonicalConfigSha256": CONFIG_HASHES,
            "imageConfigurationStatus": "NOT_RUN_REQUIRES_DEPLOYMENT_PREFLIGHT"}


def admission(manifest_path, artifact_root):
    # 실제 자격 파일·운영 주소·임의 Docker 인자는 받지 않는다.
    manifest_path = confined(manifest_path, artifact_root)
    doc = json.loads(manifest_path.read_text(encoding="utf-8"))
    need(set(doc) == {"schemaVersion", "mode", "sourceManifestPath", "sourceManifestSha256",
                      "candidate", "recovery", "mysql", "testCa", "expected"}, "MANIFEST_FIELDS")
    need(doc["schemaVersion"] == 1 and doc["mode"] == "schema-only", "UNSUPPORTED_PROOF_MODE")
    candidate = image_spec(doc["candidate"], "candidate")
    recovery = image_spec(doc["recovery"], "recovery")
    image_spec(doc["mysql"], "mysql", revision=False)
    need(candidate["image"] != recovery["image"] and candidate["expectedImageId"] != recovery["expectedImageId"]
         and candidate["expectedRevision"] != recovery["expectedRevision"], "SAME_IMAGE_FALLBACK_FORBIDDEN")
    need(doc["expected"] == {"schemaVersion": 41, "migrationCount": 40, "tableCount": 69}, "SCHEMA_EXPECTATION_MISMATCH")
    source = source_proof(confined(doc["sourceManifestPath"], artifact_root), doc["sourceManifestSha256"],
                          artifact_root, recovery["expectedRevision"])
    ca = doc["testCa"]
    need(isinstance(ca, dict) and set(ca) == {"path", "sha256"}, "CA_FIELDS")
    ca_path = confined(ca["path"], artifact_root)
    ca_bytes = ca_path.read_bytes()
    need(SHA256.fullmatch(ca["sha256"]) and sha(ca_bytes) == ca["sha256"], "TEST_PUBLIC_CA_HASH_MISMATCH")
    need(ca_bytes.count(b"-----BEGIN CERTIFICATE-----") == 1 and b"PRIVATE KEY" not in ca_bytes, "PUBLIC_TEST_CA_REQUIRED")
    return doc, {"manifestSha256": file_sha(manifest_path), "source": source, "testCaSha256": ca["sha256"]}


class Docker:
    def run(self, args, *, data=None, timeout=120, check=True):
        try:
            result = subprocess.run(["docker", *args], input=data, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, text=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired):
            raise RecoveryError("DOCKER_UNAVAILABLE_OR_TIMEOUT") from None
        if check and result.returncode != 0:
            # argv/stdout/stderr에는 합성 암호나 SQL이 있을 수 있으므로 고정 코드만 반환한다.
            raise RecoveryError("DOCKER_COMMAND_FAILED:" + args[0] + ":" + str(result.returncode))
        return result

    def inspect(self, kind, target):
        result = self.run([kind, "inspect", target])
        rows = json.loads(result.stdout)
        need(isinstance(rows, list) and len(rows) == 1, "INVALID_DOCKER_INSPECT")
        return rows[0]


def verify_image(docker, spec, name):
    info = docker.inspect("image", spec["image"])
    need(info.get("Id") == spec["expectedImageId"], "IMAGE_ID_MISMATCH:" + name)
    if "expectedRevision" in spec:
        labels = info.get("Config", {}).get("Labels") or {}
        need(labels.get("org.opencontainers.image.revision") == spec["expectedRevision"], "OCI_REVISION_MISMATCH:" + name)
        need(labels.get(CAPABILITY) == "strict-v1", "VALIDATE_CAPABILITY_REQUIRED:" + name)
    # 이미지 ID와 요청한 registry digest는 서로 다른 식별자이므로 둘 다 보존한다.
    if "@" in spec["image"]:
        need(spec["image"] in (info.get("RepoDigests") or []), "REPOSITORY_DIGEST_MISMATCH:" + name)
    return {"requestedImage": spec["image"], "imageId": info["Id"],
            "repoDigests": sorted(info.get("RepoDigests") or []),
            "runtimeOperatingMode": spec.get("runtimeOperatingMode"),
            "imageConfigurationStatus": "NOT_RUN_REQUIRES_DEPLOYMENT_PREFLIGHT",
            "labels": {key: value for key, value in (info.get("Config", {}).get("Labels") or {}).items()
                       if key in {CAPABILITY, "org.opencontainers.image.revision"}}}


def parse_validation(stdout):
    need(len(re.findall(r"(?m)^LL_DATABASE_VALIDATION_COMPLETE$", stdout)) == 1, "VALIDATION_MARKER_MISSING_OR_DUPLICATE")
    versions = re.findall(r"(?m)^LL_DATABASE_SCHEMA_VERSION: ([0-9]{1,9})$", stdout)
    executions = re.findall(r"(?m)^LL_DATABASE_MIGRATIONS_EXECUTED: ([0-9]+)$", stdout)
    need(len(versions) == 1 and int(versions[0]) == 41, "VALIDATION_SCHEMA_NOT_41")
    need(executions == ["0"], "VALIDATION_APPLIED_MIGRATIONS")
    need("LL_MIGRATION_COMPLETE" not in stdout, "VALIDATION_RAN_MIGRATE")
    return {"schemaVersion": 41, "migrationsExecuted": 0}


def synthetic_seed_sql():
    # 이 자료는 실제 사용자·답안과 무관하며 FK/CHECK 및 저장 JSON 보존만 검증한다.
    h = "a" * 64
    result = {"policyVersion": POLICY, "resultPolicy": "REFERENCE_ONLY", "fixtureKind": "SYNTHETIC_RECOVERY_V14",
              "requirementCoverage": [], "necessaryCorrections": [], "optionalImprovements": []}
    packed = canonical(result)
    hex_json = "CONVERT(0x" + packed.hex() + " USING utf8mb4)"
    sql = f"""
INSERT INTO language_learning_learner(user_id,status,identity_version,created_at,updated_at) VALUES(910000001,'ACTIVE',0,NOW(6),NOW(6));
INSERT INTO language_learning_curated_writing_catalog(id,version,content_hash,release_id,review_status,origin_language,learning_language,writing_type,target_band,semantic_key,topic_key,content_json,created_at,updated_at)
VALUES('recovery-synthetic',1,'{h}','recovery-synthetic','AUTO_REVIEWED','ko','ja','GUIDED',2,'recovery-synthetic','recovery-synthetic',JSON_OBJECT(),NOW(6),NOW(6));
INSERT INTO language_learning_curated_writing_set(id,user_id,learning_date,writing_type,policy_version,result_policy,release_id,origin_language,learning_language,base_band,status,target_item_count,planned_slots_json,created_at,updated_at)
VALUES(910000001,910000001,'2026-10-18','GUIDED','curated-writing-variable-n-v1','REFERENCE_ONLY','recovery-synthetic','ko','ja',2,'COMPLETED',1,JSON_ARRAY(),NOW(6),NOW(6));
INSERT INTO language_learning_curated_writing_item(id,set_id,user_id,item_order,difficulty,target_band,catalog_id,catalog_version,content_hash,semantic_key,public_json,private_json,content_revision,item_policy_version,created_at,updated_at)
VALUES(910000001,910000001,910000001,1,'NORMAL',2,'recovery-synthetic',1,'{h}','recovery-synthetic',JSON_OBJECT(),JSON_OBJECT(),'{h}','curated-writing-variable-n-v1',NOW(6),NOW(6));
INSERT INTO language_learning_curated_writing_answer(id,item_id,user_id,attempt_date,answer_text,content_revision,submitted_at)
VALUES(910000001,910000001,910000001,'2026-10-18','SYNTHETIC_RECOVERY_ANSWER','{h}',NOW(6));
INSERT INTO language_learning_curated_writing_feedback(answer_id,item_id,set_id,user_id,policy_version,status,answer_hash,content_revision,source_hash,request_hash,result_json,result_hash,created_at,updated_at)
VALUES(910000001,910000001,910000001,910000001,'{POLICY}','SUCCEEDED','{h}','{h}','{h}','{h}',{hex_json},'{sha(packed)}',NOW(6),NOW(6));
"""
    return sql, result


class IsolatedProof:
    def __init__(self, docker, doc, artifact_root):
        self.docker = docker
        self.doc = doc
        self.root = artifact_root
        self.task = uuid.uuid4().hex
        self.name = "ll-recovery-" + self.task
        self.network = None
        self.containers = []
        self.password = secrets.token_hex(24)
        self.runtime_password = secrets.token_hex(24)
        self.private = artifact_root / ("private-" + self.task)
        self.cleanup_receipt = []
        self.evidence = {}

    def owned_labels(self):
        return ["--label", "io.translacat.verification.owner=" + OWNER,
                "--label", "io.translacat.verification.task=" + self.task]

    def assert_owned(self, kind, identifier):
        info = self.docker.inspect(kind, identifier)
        labels = (info.get("Config", {}).get("Labels") if kind == "container" else info.get("Labels")) or {}
        need(labels.get("io.translacat.verification.owner") == OWNER
             and labels.get("io.translacat.verification.task") == self.task, "CLEANUP_OWNERSHIP_MISMATCH")
        need(info.get("Id") == identifier, "RESOURCE_ID_MISMATCH")
        if kind == "container":
            need(not info.get("HostConfig", {}).get("PortBindings"), "UNEXPECTED_HOST_PORT")
        return info

    def create_container(self, args):
        result = self.docker.run(["create", *self.owned_labels(), *args])
        identifier = result.stdout.strip()
        need(re.fullmatch(r"[0-9a-f]{64}", identifier) is not None, "CONTAINER_ID_INVALID")
        self.containers.append(identifier)
        self.assert_owned("container", identifier)
        return identifier

    def sql(self, text, *, runtime=False, check=True):
        # 비밀번호는 argv/env/로그 대신 Docker exec stdin에만 넣는다.
        username = "recovery_runtime" if runtime else "root"
        password = self.runtime_password if runtime else self.password
        script = f"export MYSQL_PWD='{password}'\nexec mysql --protocol=socket --batch --raw --skip-column-names -u{username} translacat_ll <<'RECOVERY_SQL'\n{text}\nRECOVERY_SQL\n"
        return self.docker.run(["exec", "-i", self.mysql, "sh"], data=script, check=check)

    def snapshot(self):
        # 전체 history와 CHECK 정의, 합성 행, 공식 데이터 테이블의 개수를 출력 없이 해시한다.
        queries = {
            "history": "SELECT installed_rank,version,description,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank;",
            "checks": "SELECT tc.TABLE_NAME,tc.CONSTRAINT_NAME,cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.CONSTRAINT_TYPE='CHECK' ORDER BY tc.TABLE_NAME,tc.CONSTRAINT_NAME;",
            "synthetic": "SELECT * FROM language_learning_curated_writing_feedback ORDER BY id;",
            "owners": "SELECT * FROM language_learning_learner ORDER BY user_id;",
            "catalog": "SELECT * FROM language_learning_curated_writing_catalog ORDER BY id,version;",
            "sets": "SELECT * FROM language_learning_curated_writing_set ORDER BY id;",
            "items": "SELECT * FROM language_learning_curated_writing_item ORDER BY id;",
            "answers": "SELECT * FROM language_learning_curated_writing_answer ORDER BY id;",
            "writingRows": "SELECT 'set',COUNT(*) FROM language_learning_curated_writing_set UNION ALL SELECT 'item',COUNT(*) FROM language_learning_curated_writing_item UNION ALL SELECT 'answer',COUNT(*) FROM language_learning_curated_writing_answer;",
        }
        proof = {key: sha(self.sql(query).stdout.encode()) for key, query in queries.items()}
        metadata = self.sql("SELECT MAX(CAST(version AS UNSIGNED)),COUNT(*),SUM(success=0) FROM flyway_schema_history; SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();").stdout.strip().splitlines()
        need(len(metadata) == 2, "SCHEMA_METADATA_INVALID")
        version, count, failed = [int(x) for x in metadata[0].split("\t")]
        need((version, count, failed, int(metadata[1])) == (41, 40, 0, 69), "SCHEMA_METADATA_MISMATCH")
        proof["schemaVersion"] = version
        proof["migrationCount"] = count
        proof["tableCount"] = 69
        return proof

    def database_job(self, key, *, migrate):
        destination = self.private / key
        destination.mkdir(mode=0o755)
        ca = Path(self.doc["testCa"]["path"]).read_bytes()
        for name in ("service-ca.pem", "mysql-ca.pem"):
            (destination / name).write_bytes(ca)
        values = {
            "TZ": "UTC", "DB_JDBC_URL": f"jdbc:mysql://{self.name}-mysql:3306/translacat_ll?sslMode=DISABLED&allowPublicKeyRetrieval=true",
            "DB_USERNAME": "root" if migrate else "recovery_runtime",
            "DB_PASSWORD": self.password if migrate else self.runtime_password,
        }
        if migrate:
            values["LL_ALLOW_MIGRATIONS"] = "true"
        (destination / "runtime.env").write_text("".join(f"{name}={value}\n" for name, value in values.items()), encoding="utf-8")
        for path in destination.iterdir():
            os.chmod(path, 0o644)
        # 실행 image는 사전 검사한 불변 ID이며 DB 단독 명령만 전달한다.
        image = self.doc[key]["expectedImageId"]
        identifier = self.create_container(["--name", self.name + "-" + key, "--network", self.network,
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--tmpfs", "/tmp:rw,nosuid,nodev,size=96m",
            image, "--migrate-database" if migrate else "--validate-database"])
        # Docker Desktop의 D bind 경로에 의존하지 않는다. 시작 전 합성 설정만 복사한다.
        self.docker.run(["cp", str(destination), identifier + ":/run/config"])
        return self.docker.run(["start", "--attach", identifier], timeout=360).stdout

    def execute(self):
        images = {key: verify_image(self.docker, self.doc[key], key) for key in ("candidate", "recovery", "mysql")}
        self.private.mkdir(mode=0o700)
        self.evidence["images"] = images
        self.checkpoint("IMAGES_VERIFIED")
        try:
            # 새 private network만 사용하며 기존 network·DB·container를 채택하지 않는다.
            output = self.docker.run(["network", "create", "--internal", *self.owned_labels(), self.name])
            self.network = output.stdout.strip()
            need(re.fullmatch(r"[0-9a-f]{64}", self.network) is not None, "NETWORK_ID_INVALID")
            self.assert_owned("network", self.network)
            mysql_env = self.private / "mysql.env"
            mysql_env.write_text("MYSQL_ROOT_PASSWORD=" + self.password + "\nMYSQL_DATABASE=translacat_ll\nMYSQL_ROOT_HOST=%\n", encoding="utf-8")
            os.chmod(mysql_env, 0o600 if os.name != "nt" else 0o644)
            self.mysql = self.create_container(["--name", self.name + "-mysql", "--network", self.network,
                "--network-alias", self.name + "-mysql", "--env-file", str(mysql_env),
                "--tmpfs", "/var/lib/mysql:rw,nosuid,nodev,size=768m", "--tmpfs", "/var/run/mysqld:rw,nosuid,nodev,size=16m",
                "--memory", "1g", "--cpus", "1", self.doc["mysql"]["expectedImageId"]])
            self.docker.run(["start", self.mysql])
            ready = False
            for _ in range(60):
                result = self.docker.run(["exec", self.mysql, "mysqladmin", "--protocol=socket", "ping", "--silent"], check=False)
                if result.returncode == 0:
                    ready = True
                    break
                time.sleep(1)
            need(ready, "ISOLATED_MYSQL_NOT_READY")
            migrated = self.database_job("candidate", migrate=True)
            need("LL_MIGRATION_COMPLETE" in migrated, "CANDIDATE_MIGRATION_MARKER_MISSING")
            seed, result = synthetic_seed_sql()
            self.sql(seed)
            self.sql(f"CREATE USER 'recovery_runtime'@'%' IDENTIFIED BY '{self.runtime_password}'; GRANT SELECT,INSERT,UPDATE,DELETE ON translacat_ll.* TO 'recovery_runtime'@'%';")
            # FK/CHECK의 음성 대조는 원래 행을 바꾸지 못한 채 거절되어야 한다.
            negative = self.sql("UPDATE language_learning_curated_writing_feedback SET policy_version='unapproved-recovery-policy';", check=False)
            need(negative.returncode != 0 and re.search(r"ERROR 3819\b", negative.stderr), "INVALID_POLICY_CHECK_NOT_PROVEN")
            ddl_negative = self.sql("CREATE TABLE recovery_ddl_must_not_exist(id INT);", runtime=True, check=False)
            need(ddl_negative.returncode != 0 and re.search(r"ERROR 1142\b", ddl_negative.stderr), "RUNTIME_DDL_DENIAL_NOT_PROVEN")
            before = self.snapshot()
            self.evidence.update(images=images, before=before, syntheticResultSha256=sha(canonical(result)))
            self.checkpoint("BEFORE_RECOVERY_VALIDATE")
            validation = parse_validation(self.database_job("recovery", migrate=False))
            after = self.snapshot()
            self.evidence.update(validation=validation, after=after)
            self.checkpoint("AFTER_RECOVERY_VALIDATE")
            need(before == after, "VALIDATION_MUTATED_SCHEMA_OR_DATA")
            return {"status": "SCHEMA_VALIDATED_READER_NOT_RUN", "deploymentReady": False,
                    "images": images, "validation": validation, "before": before, "after": after,
                    "syntheticResultSha256": sha(canonical(result)), "invalidPolicyRejected": True,
                    "runtimeDdlRejected": True, "reader": {"status": "NOT_RUN", "reason": "NO_APPROVED_STORED_READER_CONFIGURATION"},
                    "providerCalls": 0, "applicationWorkersStarted": 0}
        finally:
            self.cleanup()
            # 합성 자격 파일만 정리하고 검증 기록·receipt·source manifest는 보존한다.
            if self.private.exists():
                for path in sorted(self.private.rglob("*"), reverse=True):
                    if path.is_file():
                        path.unlink()
                    elif path.is_dir():
                        path.rmdir()
                self.private.rmdir()

    def checkpoint(self, phase):
        journal = self.root / (self.name + "-journal.jsonl")
        with journal.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"phase": phase, "evidence": self.evidence}, sort_keys=True) + "\n")

    def cleanup(self):
        # 생성 당시 ID와 owner/task label이 모두 일치한 자원만 정리한다.
        for identifier in reversed(self.containers):
            try:
                self.assert_owned("container", identifier)
                self.docker.run(["rm", "--force", identifier])
                self.cleanup_receipt.append({"kind": "container", "id": identifier, "removed": True})
            except RecoveryError as error:
                self.cleanup_receipt.append({"kind": "container", "id": identifier, "removed": False, "code": str(error)})
        if self.network:
            try:
                self.assert_owned("network", self.network)
                self.docker.run(["network", "rm", self.network])
                self.cleanup_receipt.append({"kind": "network", "id": self.network, "removed": True})
            except RecoveryError as error:
                self.cleanup_receipt.append({"kind": "network", "id": self.network, "removed": False, "code": str(error)})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execute-isolated", action="store_true", help="Requires separate current approval; creates only owned temporary resources.")
    args = parser.parse_args(argv)
    output = confined(args.output, ARTIFACT_ROOT)
    need(not output.exists(), "OUTPUT_ALREADY_EXISTS")
    receipt = {"schemaVersion": 1, "toolSha256": file_sha(Path(__file__)), "status": "FAILED", "deploymentReady": False}
    engine = None
    exit_code = 1
    try:
        doc, proof = admission(args.manifest, ARTIFACT_ROOT)
        receipt["admission"] = proof
        if args.execute_isolated:
            engine = IsolatedProof(Docker(), doc, ARTIFACT_ROOT)
            receipt.update(engine.execute())
        else:
            receipt.update(status="ADMITTED_EXECUTION_NOT_RUN", reader={"status": "NOT_RUN"}, providerCalls=0)
        exit_code = 0
    except (RecoveryError, OSError, ValueError, KeyError, TypeError):
        # 원문 파일·Docker 출력 대신 고정 오류 코드만 보고한다.
        error = sys.exc_info()[1]
        receipt["errorCode"] = str(error) if isinstance(error, RecoveryError) else "INVALID_INPUT_OR_IO"
    if engine is not None:
        receipt.update(engine.evidence)
        receipt["cleanup"] = engine.cleanup_receipt
        if any(not row["removed"] for row in engine.cleanup_receipt):
            receipt["status"] = "FAILED_CLEANUP_INCOMPLETE"
            exit_code = 1
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(receipt, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print("WRITING_RECOVERY_RECEIPT: " + receipt["status"])
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
