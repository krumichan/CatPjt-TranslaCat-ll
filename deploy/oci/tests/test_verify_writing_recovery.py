"""Fake Docker/stdlib contract tests. No real Docker, DB, network, Git or AI."""
import copy
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("writing_recovery", HERE / "verify_writing_recovery.py")
v = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(v)


class FakeDocker:
    def __init__(self, manifest):
        self.manifest = manifest
        self.commands = []
        self.resources = {}
        self.n = 0
        self.mutate = False
        self.snapshot_count = 0
        self.bad_capability = False
        self.fail_validate = False
        self.wrong_check_error = False
        self.fail_remove = False

    def inspect(self, kind, target):
        if kind == "image":
            for name in ("candidate", "recovery", "mysql"):
                row = self.manifest[name]
                if row["image"] == target:
                    labels = {"org.opencontainers.image.revision": row.get("expectedRevision"),
                              v.CAPABILITY: "missing" if self.bad_capability else "strict-v1"}
                    return {"Id": row["expectedImageId"], "Config": {"Labels": labels},
                            "RepoDigests": [target] if "@" in target else []}
        return self.resources[target]

    def run(self, args, *, data=None, timeout=120, check=True):
        self.commands.append((list(args), data))
        output, error, code = "", "", 0
        if args[:2] == ["network", "create"]:
            identifier = "e" * 64
            labels = self.labels(args)
            self.resources[identifier] = {"Id": identifier, "Labels": labels}
            output = identifier
        elif args[0] == "create":
            self.n += 1
            identifier = str(self.n) * 64
            self.resources[identifier] = {"Id": identifier, "Config": {"Labels": self.labels(args)},
                                           "HostConfig": {"PortBindings": {}}}
            output = identifier
        elif args[:2] == ["start", "--attach"]:
            if self.n == 2:
                output = "LL_MIGRATION_COMPLETE\n"
            else:
                output = "LL_DATABASE_VALIDATION_COMPLETE\nLL_DATABASE_SCHEMA_VERSION: 041\nLL_DATABASE_MIGRATIONS_EXECUTED: 0\n"
                if self.fail_validate:
                    code, error = 1, "sanitized"
        elif args[0] == "exec" and data:
            if "unapproved-recovery-policy" in data:
                code, error = 1, "ERROR 3819 (HY000)" if not self.wrong_check_error else "ERROR 1064 (42000)"
            elif "recovery_ddl_must_not_exist" in data:
                code, error = 1, "ERROR 1142 (42000)"
            elif "SELECT MAX(CAST" in data:
                output = "41\t40\t0\n69\n"
            elif "SELECT" in data:
                output = "synthetic-only\n"
                if "SELECT installed_rank" in data:
                    self.snapshot_count += 1
                    if self.mutate and self.snapshot_count == 2:
                        output = "modified\n"
        elif args[:2] == ["rm", "--force"] and self.fail_remove:
            code = 1
        if check and code:
            raise v.RecoveryError("FAKE_COMMAND_FAILED")
        return subprocess.CompletedProcess(args, code, output, error)

    @staticmethod
    def labels(args):
        result = {}
        for i, arg in enumerate(args):
            if arg == "--label":
                key, value = args[i + 1].split("=", 1)
                result[key] = value
        return result


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        # 준비: 모든 테스트 자료는 호출자가 지정한 임시 artifact 디렉터리 안에 둔다.
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        context = self.root / "recovery-context"
        context.mkdir()
        (context / "source.txt").write_text("synthetic source", encoding="utf-8")
        self.original_config_hashes = copy.deepcopy(v.CONFIG_HASHES)
        synthetic_pins = {}
        for rel in v.CONFIG_HASHES:
            path = context / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic offline config: " + rel, encoding="utf-8")
            synthetic_pins[rel] = v.file_sha(path)
        self.config_patch = patch.object(v, "CONFIG_HASHES", synthetic_pins)
        self.config_patch.start()
        self.addCleanup(self.config_patch.stop)
        recovery_revision = "c" * 40
        source = {"baseRevision": v.BASE_REVISION, "revision": recovery_revision,
                  "sourceRoot": str(context), "exactPatchPaths": ["source.txt"],
                  "files": [{"path": path.relative_to(context).as_posix(), "sha256": v.file_sha(path)} for path in context.rglob("*") if path.is_file()],
                  "canonicalConfigSha256": synthetic_pins,
                  "operatingConditions": {"curatedEnabled": False, "feedbackEnabled": False, "qaOnly": False, "variableNEnabled": False}}
        source_path = self.root / "source-manifest.json"
        source_path.write_text(json.dumps(source), encoding="utf-8")
        ca = self.root / "public-test-ca.pem"
        ca.write_text("-----BEGIN CERTIFICATE-----\nSYNTHETIC_OFFLINE_PUBLIC_FIXTURE\n-----END CERTIFICATE-----", encoding="utf-8")
        self.doc = {
            "schemaVersion": 1, "mode": "schema-only", "sourceManifestPath": str(source_path),
            "sourceManifestSha256": v.file_sha(source_path),
            "candidate": {"image": "sha256:" + "a" * 64, "expectedImageId": "sha256:" + "a" * 64, "expectedRevision": "b" * 40, "runtimeOperatingMode": {"mode": "CURATED_DISABLED", "curatedEnabled": False, "feedbackEnabled": False, "qaOnly": False, "variableNEnabled": False}},
            "recovery": {"image": "sha256:" + "c" * 64, "expectedImageId": "sha256:" + "c" * 64, "expectedRevision": recovery_revision, "runtimeOperatingMode": {"mode": "CURATED_DISABLED", "curatedEnabled": False, "feedbackEnabled": False, "qaOnly": False, "variableNEnabled": False}},
            "mysql": {"image": "sha256:" + "d" * 64, "expectedImageId": "sha256:" + "d" * 64},
            "testCa": {"path": str(ca), "sha256": v.file_sha(ca)},
            "expected": {"schemaVersion": 41, "migrationCount": 40, "tableCount": 69},
        }
        self.manifest = self.root / "manifest.json"
        self.save()

    def tearDown(self):
        self.tmp.cleanup()

    def save(self):
        self.manifest.write_text(json.dumps(self.doc), encoding="utf-8")

    def rejected(self, change):
        change(self.doc)
        self.save()
        with self.assertRaises(v.RecoveryError):
            v.admission(self.manifest, self.root)

    def test_admission_passes_without_running_docker(self):
        # 실행·검증: 기본 명령은 Docker 객체도 만들지 않고 미실행 receipt만 기록한다.
        output = self.root / "receipt.json"
        with patch.object(v, "ARTIFACT_ROOT", self.root), patch.object(v, "Docker") as docker:
            self.assertEqual(v.main(["--manifest", str(self.manifest), "--output", str(output)]), 0)
            docker.assert_not_called()
        receipt = json.loads(output.read_text())
        self.assertEqual(receipt["status"], "ADMITTED_EXECUTION_NOT_RUN")
        self.assertFalse(receipt["deploymentReady"])

    def test_mutable_image_rejected(self):
        self.rejected(lambda doc: doc["recovery"].update(image="example/recovery:latest"))

    def test_same_image_fallback_rejected(self):
        self.rejected(lambda doc: doc.update(recovery=copy.deepcopy(doc["candidate"])))

    def test_revision_required(self):
        self.rejected(lambda doc: doc["recovery"].update(expectedRevision="main"))

    def test_unknown_fields_cannot_smuggle_host_database(self):
        self.rejected(lambda doc: doc.update(databaseHost="production.invalid"))

    def test_reader_mode_not_silently_claimed(self):
        self.rejected(lambda doc: doc.update(mode="schema-and-reader"))

    def test_schema_expectations_not_relaxed(self):
        self.rejected(lambda doc: doc["expected"].update(schemaVersion=39))

    def test_enabled_curated_operating_mode_rejected(self):
        self.rejected(lambda doc: doc["recovery"]["runtimeOperatingMode"].update(curatedEnabled=True))

    def test_string_false_is_not_boolean_false(self):
        self.rejected(lambda doc: doc["candidate"]["runtimeOperatingMode"].update(feedbackEnabled="false"))

    def test_zero_is_not_boolean_false(self):
        self.rejected(lambda doc: doc["candidate"]["runtimeOperatingMode"].update(feedbackEnabled=0))

    def test_source_operating_condition_mismatch_rejected(self):
        source_path = Path(self.doc["sourceManifestPath"])
        source = json.loads(source_path.read_text())
        source["operatingConditions"]["feedbackEnabled"] = True
        source_path.write_text(json.dumps(source))
        self.rejected(lambda doc: doc.update(sourceManifestSha256=v.file_sha(source_path)))
    def test_qa_only_or_variable_n_cannot_be_enabled(self):
        for key in ("qaOnly", "variableNEnabled"):
            with self.subTest(key=key):
                doc = copy.deepcopy(self.doc)
                doc["recovery"]["runtimeOperatingMode"][key] = True
                with self.assertRaises(v.RecoveryError):
                    v.image_spec(doc["recovery"], "recovery")

    def test_canonical_production_pins_are_exact(self):
        self.assertEqual(self.original_config_hashes, {
            "src/main/resources/application.yaml": "20ed92b2e46ee5a6f9881b08b25fbaeef915361e1af3446f23cfb5a71a76b53f",
            "config/application-prod.yaml": "08b645ae807d31edb797e32c27e72fe2f7653f14735927c21ea2cab748ce97fe",
        })

    def test_crlf_config_uses_lf_canonical_pin_but_keeps_raw_file_hash(self):
        # 준비: 동일 공개 설정의 LF pin과 CRLF 원본 SHA를 서로 다르게 고정한다.
        source_path = Path(self.doc["sourceManifestPath"])
        source = json.loads(source_path.read_text())
        lf = b"synthetic offline config\nsecond line\n"
        raw = lf.replace(b"\n", b"\r\n")
        for rel in v.CONFIG_HASHES:
            path = self.root / "recovery-context" / rel
            path.write_bytes(raw)
            v.CONFIG_HASHES[rel] = v.sha(lf)
            source["canonicalConfigSha256"][rel] = v.sha(lf)
            for row in source["files"]:
                if row["path"] == rel:
                    row["sha256"] = v.sha(raw)
        source_path.write_text(json.dumps(source))
        self.doc["sourceManifestSha256"] = v.file_sha(source_path)
        self.save()

        # 실행·검증: raw manifest 대조와 canonical 대조가 함께 통과한다.
        _, proof = v.admission(self.manifest, self.root)
        self.assertNotEqual(v.sha(raw), v.sha(lf))
        self.assertEqual(proof["source"]["canonicalConfigSha256"], v.CONFIG_HASHES)
        self.assertEqual(source["files"][0]["sha256"], v.file_sha(self.root / "recovery-context" / source["files"][0]["path"]))
    def test_changed_config_cannot_be_reauthorized_by_manifest_hash(self):
        source_path = Path(self.doc["sourceManifestPath"])
        source = json.loads(source_path.read_text())
        rel = "config/application-prod.yaml"
        path = self.root / "recovery-context" / rel
        path.write_text("changed config")
        for row in source["files"]:
            if row["path"] == rel:
                row["sha256"] = v.file_sha(path)
        source_path.write_text(json.dumps(source))
        self.rejected(lambda doc: doc.update(sourceManifestSha256=v.file_sha(source_path)))
    def test_source_manifest_tamper_rejected(self):
        self.rejected(lambda doc: doc.update(sourceManifestSha256="0" * 64))

    def test_source_file_tamper_rejected(self):
        (self.root / "recovery-context/source.txt").write_text("changed")
        with self.assertRaises(v.RecoveryError):
            v.admission(self.manifest, self.root)

    def test_undeclared_source_file_rejected(self):
        (self.root / "recovery-context/extra.txt").write_text("extra")
        with self.assertRaises(v.RecoveryError):
            v.admission(self.manifest, self.root)

    def test_private_key_in_ca_rejected(self):
        ca = Path(self.doc["testCa"]["path"])
        ca.write_text(ca.read_text() + "\nPRIVATE KEY")
        self.rejected(lambda doc: doc["testCa"].update(sha256=v.file_sha(ca)))

    def test_outside_artifact_path_rejected(self):
        with self.assertRaises(v.RecoveryError):
            v.confined(self.root.parent / "not-owned", self.root)

    def test_capability_checked_before_any_resource_create(self):
        docker = FakeDocker(self.doc)
        docker.bad_capability = True
        engine = v.IsolatedProof(docker, self.doc, self.root)
        with self.assertRaises(v.RecoveryError):
            engine.execute()
        self.assertEqual(docker.commands, [])

    def test_image_id_mismatch_rejected(self):
        docker = FakeDocker(self.doc)
        wrong = copy.deepcopy(self.doc["recovery"])
        wrong["expectedImageId"] = "sha256:" + "f" * 64
        with self.assertRaises(v.RecoveryError):
            v.verify_image(docker, wrong, "recovery")

    def test_leading_zero_schema_marker_is_valid(self):
        result = v.parse_validation("LL_DATABASE_VALIDATION_COMPLETE\nLL_DATABASE_SCHEMA_VERSION: 041\nLL_DATABASE_MIGRATIONS_EXECUTED: 0\n")
        self.assertEqual(result, {"schemaVersion": 41, "migrationsExecuted": 0})

    def test_nonzero_migration_execution_rejected(self):
        with self.assertRaises(v.RecoveryError):
            v.parse_validation("LL_DATABASE_VALIDATION_COMPLETE\nLL_DATABASE_SCHEMA_VERSION: 41\nLL_DATABASE_MIGRATIONS_EXECUTED: 1\n")

    def test_duplicate_marker_rejected(self):
        with self.assertRaises(v.RecoveryError):
            v.parse_validation("LL_DATABASE_VALIDATION_COMPLETE\n" * 2 + "LL_DATABASE_SCHEMA_VERSION: 41\nLL_DATABASE_MIGRATIONS_EXECUTED: 0\n")

    def test_fake_isolated_schema_proof_preserves_rows_and_never_claims_reader(self):
        # 실행: Docker는 전부 fake이며 실제 daemon·DB·Provider 연결은 없다.
        docker = FakeDocker(self.doc)
        engine = v.IsolatedProof(docker, self.doc, self.root)
        receipt = engine.execute()
        # 검증: 새 소유 자원만 사용하고 bind/host port/일반 앱 실행은 하지 않는다.
        self.assertEqual(receipt["before"], receipt["after"])
        self.assertEqual(receipt["status"], "SCHEMA_VALIDATED_READER_NOT_RUN")
        self.assertFalse(receipt["deploymentReady"])
        self.assertEqual(receipt["reader"]["status"], "NOT_RUN")
        self.assertEqual(receipt["providerCalls"], 0)
        self.assertEqual(receipt["images"]["recovery"]["imageConfigurationStatus"], "NOT_RUN_REQUIRES_DEPLOYMENT_PREFLIGHT")
        self.assertEqual(len(engine.cleanup_receipt), 4)
        self.assertTrue(all(row["removed"] for row in engine.cleanup_receipt))
        self.assertFalse(engine.private.exists())
        args = [arg for row, _ in docker.commands for arg in row]
        self.assertNotIn("--mount", args)
        self.assertNotIn("--publish", args)
        self.assertNotIn("--privileged", args)
        self.assertNotIn("--network=host", args)
        cp = [row for row, _ in docker.commands if row[0] == "cp"]
        self.assertEqual(len(cp), 2)

    def test_mutated_history_fails_and_is_preserved_before_cleanup(self):
        docker = FakeDocker(self.doc)
        docker.mutate = True
        engine = v.IsolatedProof(docker, self.doc, self.root)
        with self.assertRaisesRegex(v.RecoveryError, "VALIDATION_MUTATED"):
            engine.execute()
        self.assertNotEqual(engine.evidence["before"], engine.evidence["after"])
        journal = self.root / (engine.name + "-journal.jsonl")
        self.assertIn("AFTER_RECOVERY_VALIDATE", journal.read_text())
        self.assertTrue(all(row["removed"] for row in engine.cleanup_receipt))

    def test_wrong_negative_error_cannot_prove_check(self):
        docker = FakeDocker(self.doc)
        docker.wrong_check_error = True
        engine = v.IsolatedProof(docker, self.doc, self.root)
        with self.assertRaisesRegex(v.RecoveryError, "CHECK_NOT_PROVEN"):
            engine.execute()
        self.assertTrue(all(row["removed"] for row in engine.cleanup_receipt))

    def test_cleanup_refuses_foreign_resource(self):
        docker = FakeDocker(self.doc)
        engine = v.IsolatedProof(docker, self.doc, self.root)
        identifier = "f" * 64
        engine.containers.append(identifier)
        docker.resources[identifier] = {"Id": identifier, "Config": {"Labels": {}}, "HostConfig": {"PortBindings": {}}}
        engine.cleanup()
        self.assertFalse(engine.cleanup_receipt[0]["removed"])
        self.assertFalse(any(row[0] == "rm" for row, _ in docker.commands))

    def test_validation_failure_keeps_original_evidence_and_cleans_only_owned(self):
        docker = FakeDocker(self.doc)
        docker.fail_validate = True
        engine = v.IsolatedProof(docker, self.doc, self.root)
        with self.assertRaises(v.RecoveryError):
            engine.execute()
        self.assertEqual(engine.evidence["before"]["schemaVersion"], 41)
        self.assertTrue(all(row["removed"] for row in engine.cleanup_receipt))

    def test_receipt_reports_cleanup_failure_without_false_pass(self):
        docker = FakeDocker(self.doc)
        docker.fail_remove = True
        output = self.root / "cleanup-failure.json"
        with patch.object(v, "ARTIFACT_ROOT", self.root), patch.object(v, "Docker", return_value=docker):
            code = v.main(["--manifest", str(self.manifest), "--output", str(output), "--execute-isolated"])
        receipt = json.loads(output.read_text())
        self.assertEqual(code, 1)
        self.assertEqual(receipt["status"], "FAILED_CLEANUP_INCOMPLETE")
        self.assertEqual(receipt["before"], receipt["after"])

    def test_existing_receipt_not_overwritten(self):
        output = self.root / "existing.json"
        output.write_text("preserved")
        with patch.object(v, "ARTIFACT_ROOT", self.root):
            with self.assertRaises(v.RecoveryError):
                v.main(["--manifest", str(self.manifest), "--output", str(output)])
        self.assertEqual(output.read_text(), "preserved")


if __name__ == "__main__":
    unittest.main()
