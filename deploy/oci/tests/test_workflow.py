"""Non-applied workflow proposal tests. Never execute Docker, SSH, registry, DB or a GitHub job."""
import ast
import json
import os
from pathlib import Path
import re
import textwrap
import unittest
from unittest.mock import patch

WORKFLOW = Path(__file__).resolve().parents[3] / '.github' / 'workflows' / 'deploy.yaml'
SHA = 'a' * 40
IMAGE = 'ghcr.io/owner/project@sha256:' + 'b' * 64
RECOVERY = 'ghcr.io/owner/project@sha256:' + 'c' * 64
RECOVERY_SHA = 'd' * 40


def job(name):
    source = WORKFLOW.read_text(encoding='utf-8-sig')
    match = re.search(r'(?ms)^  ' + re.escape(name) + r':\n(.*?)(?=^  [a-z_][a-z0-9_]*:\s*$|\Z)', source)
    if not match:
        raise AssertionError('Missing workflow job: ' + name)
    return match.group(1)


def permits(name, event, ref, action, candidate=IMAGE, recovery=RECOVERY, recovery_sha=RECOVERY_SHA):
    # 실제 제안의 if 표현식만 제한된 AST로 읽는다. eval/외부 작업은 실행하지 않는다.
    match = re.search(r'(?m)^    if: >-\n((?:      .+\n)+)', job(name))
    if not match:
        raise AssertionError('Missing folded job condition')
    expr = ' '.join(line.strip() for line in match.group(1).splitlines())
    tree = ast.parse(expr.replace('&&', ' and ').replace('||', ' or '), mode='eval')
    values = {'github.event_name': event, 'github.ref': ref, 'inputs.action': action,
              'inputs.candidate_image': candidate, 'inputs.recovery_image': recovery, 'inputs.recovery_sha': recovery_sha}

    def visit(node):
        if isinstance(node, ast.Expression):
            return visit(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name):
            return values[node.value.id + '.' + node.attr]
        if isinstance(node, ast.BoolOp) and isinstance(node.op, (ast.And, ast.Or)):
            result = [visit(item) for item in node.values]
            return all(result) if isinstance(node.op, ast.And) else any(result)
        if isinstance(node, ast.Compare) and len(node.ops) == 1 and isinstance(node.ops[0], (ast.Eq, ast.NotEq)):
            same = visit(node.left) == visit(node.comparators[0])
            return same if isinstance(node.ops[0], ast.Eq) else not same
        raise AssertionError('Unsupported condition syntax')

    return bool(visit(tree))


def step_script(name, title):
    marker = '      - name: ' + title + '\n'
    body = job(name)
    if body.count(marker) != 1:
        raise AssertionError('Expected unique step: ' + title)
    body = re.split(r'(?m)^      - ', body.split(marker, 1)[1], maxsplit=1)[0]
    return textwrap.dedent(body.split('        run: |\n', 1)[1])


def step_python(name, title):
    source = step_script(name, title)
    match = re.search(r"python3 - <<'PY'\n(.*?)\nPY(?:\n|$)", source, re.S)
    if not match:
        raise AssertionError('Expected literal Python heredoc')
    return compile(match.group(1), '<non-applied-workflow-proposal>', 'exec')


def manual_env(**changes):
    result = dict(EVENT_NAME='workflow_dispatch', REQUESTED_REF='refs/heads/main', REQUESTED_ACTION='deploy',
                  CANDIDATE_IMAGE=IMAGE, RECOVERY_IMAGE=RECOVERY, RECOVERY_SHA=RECOVERY_SHA)
    result.update(changes)
    return result


class WorkflowProposalV2Tests(unittest.TestCase):
    def test_main_push_builds_but_never_automatically_ships(self):
        # 정책 강화: main push는 검증·이미지 발행까지만 하고 운영 ship은 절대 시작하지 않는다.
        self.assertTrue(permits('build_image', 'push', 'refs/heads/main', ''))
        self.assertFalse(permits('release', 'push', 'refs/heads/main', ''))
        for name in ('build_image', 'release'):
            self.assertFalse(permits(name, 'push', 'refs/heads/feature', ''))
            self.assertFalse(permits(name, 'pull_request', 'refs/pull/1/merge', ''))

    def test_build_only_selected_ref_cannot_ship_and_accepts_no_deployment_inputs(self):
        # 준비 / 실행 / 검증: main·branch·tag의 build-only는 운영 image 인자 없이 검증·발행만 수행한다.
        for ref in ('refs/heads/main', 'refs/heads/recovery-compatible', 'refs/tags/reviewed'):
            with self.subTest(ref=ref):
                self.assertTrue(permits('build_image', 'workflow_dispatch', ref, 'build-only'))
                self.assertFalse(permits('release', 'workflow_dispatch', ref, 'build-only'))
                env = manual_env(REQUESTED_REF=ref, REQUESTED_ACTION='build-only',
                                 CANDIDATE_IMAGE='', RECOVERY_IMAGE='', RECOVERY_SHA='')
                with patch.dict(os.environ, env, clear=True):
                    exec(step_python('verify', 'Validate manual action and immutable deployment identities'), {})

    def test_deploy_requires_main_known_action_and_complete_job_inputs(self):
        # 준비 / 실행 / 검증: non-main/unknown/비어 있는 필수 identity는 운영 job에 진입하지 못한다.
        self.assertTrue(permits('release', 'workflow_dispatch', 'refs/heads/main', 'deploy'))
        for action, ref in (('deploy', 'refs/heads/feature'), ('unknown', 'refs/heads/main')):
            with self.subTest(action=action, ref=ref):
                self.assertFalse(permits('build_image', 'workflow_dispatch', ref, action))
                self.assertFalse(permits('release', 'workflow_dispatch', ref, action))
                with patch.dict(os.environ, manual_env(REQUESTED_ACTION=action, REQUESTED_REF=ref), clear=True):
                    with self.assertRaises(SystemExit):
                        exec(step_python('verify', 'Validate manual action and immutable deployment identities'), {})
        for changes in (dict(candidate=''), dict(recovery=''), dict(recovery_sha='')):
            self.assertFalse(permits('release', 'workflow_dispatch', 'refs/heads/main', 'deploy', **changes))

    def test_same_verify_precedes_build_and_deploy_without_skipping_checks(self):
        # 준비 / 실행 / 검증: deploy는 같은 verify만 의존하며 build job을 다시 실행할 의존성이 없다.
        self.assertIn('needs: verify', job('build_image'))
        self.assertIn('needs: verify', job('release'))
        self.assertNotIn('needs: [verify, build_image]', job('release'))
        self.assertIn("databaseIntegrationTest --tests '*CuratedWritingStoreIntegrationTest*'", job('verify'))
        self.assertIn("writingHttpDatabaseIntegrationTest --tests '*CuratedWritingHttpDatabaseIntegrationTest*'", job('verify'))
        self.assertIn('docker build --platform linux/amd64 --target runtime', job('verify'))
        self.assertNotIn('continue-on-error', WORKFLOW.read_text())
        for name in ('verify', 'build_image', 'release'):
            self.assertIn('ref: ${{ github.sha }}', job(name))
            self.assertIn('persist-credentials: false', job(name))

    def test_production_environment_and_secrets_remain_release_only(self):
        # 준비 / 실행 / 검증: verify는 secret 0, image job은 GITHUB_TOKEN만 받고 운영 secret은 release에 둔다.
        self.assertNotIn('environment:', job('verify'))
        self.assertNotIn('secrets.', job('verify'))
        image = job('build_image')
        self.assertNotIn('environment:', image)
        self.assertEqual(re.findall(r'secrets\.([A-Z_]+)', image), ['GITHUB_TOKEN'])
        self.assertNotIn('ship.py', image)
        self.assertNotIn('deploy.py', image)
        self.assertIn('packages: write', image)
        self.assertIn('environment: production', job('release'))
        self.assertIn('packages: read', job('release'))
        self.assertNotIn('packages: write', job('release'))

    def test_deploy_requires_immutable_candidate_and_complete_recovery_pair(self):
        # 정책 강화: v1의 empty recovery 허용을 제거하고 기존 candidate digest까지 필수로 한다.
        with patch.dict(os.environ, manual_env(), clear=True):
            exec(step_python('verify', 'Validate manual action and immutable deployment identities'), {})
        mutations = (dict(CANDIDATE_IMAGE=''), dict(RECOVERY_IMAGE=''), dict(RECOVERY_SHA=''),
                     dict(CANDIDATE_IMAGE='ghcr.io/owner/project:latest'),
                     dict(RECOVERY_IMAGE='ghcr.io/owner/project:latest'), dict(RECOVERY_SHA='main'),
                     dict(CANDIDATE_IMAGE=IMAGE + '; touch /tmp/unreviewed'))
        for changes in mutations:
            with self.subTest(changes=changes), patch.dict(os.environ, manual_env(**changes), clear=True):
                with self.assertRaises(SystemExit):
                    exec(step_python('verify', 'Validate manual action and immutable deployment identities'), {})

    def test_build_only_rejects_candidate_or_recovery_inputs(self):
        # 준비 / 실행 / 검증: build-only에 운영 인자를 주어도 조용히 무시하지 않는다.
        base = manual_env(REQUESTED_ACTION='build-only', CANDIDATE_IMAGE='', RECOVERY_IMAGE='', RECOVERY_SHA='')
        for changes in (dict(CANDIDATE_IMAGE=IMAGE), dict(RECOVERY_IMAGE=RECOVERY), dict(RECOVERY_SHA=RECOVERY_SHA)):
            with self.subTest(changes=changes), patch.dict(os.environ, {**base, **changes}, clear=True):
                with self.assertRaises(SystemExit):
                    exec(step_python('verify', 'Validate manual action and immutable deployment identities'), {})

    def test_release_forwards_exact_existing_candidate_digest_without_shell_or_rebuild(self):
        # 준비: 서로 다른 candidate/recovery digest를 사용하고 실제 프로세스는 mock으로 막는다.
        env = dict(CANDIDATE_IMAGE=IMAGE, RECOVERY_IMAGE=RECOVERY, RECOVERY_SHA=RECOVERY_SHA, GITHUB_SHA=SHA)
        with patch.dict(os.environ, env, clear=True), patch('subprocess.run') as run:
            # 실행
            exec(step_python('release', 'Deploy existing verified candidate using SSH host keys'), {})

        # 검증: 검증된 candidate를 그대로 전달하며 새 build digest로 바꾸지 않는다.
        run.assert_called_once_with(['python3', 'deploy/oci/ship.py', '--service', 'll', '--image', IMAGE,
                                     '--recovery-image', RECOVERY, '--recovery-sha', RECOVERY_SHA], check=True)
        self.assertNotIn('shell', run.call_args.kwargs)
        release = job('release')
        self.assertNotIn('docker build', release)
        self.assertNotIn('docker push', release)
        self.assertNotIn('needs.build_image.outputs', release)
        self.assertIn('CANDIDATE_IMAGE: ${{ inputs.candidate_image }}', release)
        self.assertNotIn('allow_app_rollback_after_migration', WORKFLOW.read_text())

    def test_invalid_identity_never_starts_ship(self):
        # 준비 / 실행 / 검증: release 자체도 형식과 필수값을 다시 검사해 subprocess 호출 전에 실패한다.
        env = dict(CANDIDATE_IMAGE=IMAGE, RECOVERY_IMAGE=RECOVERY, RECOVERY_SHA=RECOVERY_SHA, GITHUB_SHA=SHA)
        for change in (dict(CANDIDATE_IMAGE=''), dict(RECOVERY_IMAGE=''), dict(RECOVERY_SHA=''),
                       dict(CANDIDATE_IMAGE='ghcr.io/owner/project:latest'),
                       dict(RECOVERY_SHA='main'), dict(GITHUB_SHA='main')):
            with self.subTest(change=change), patch.dict(os.environ, {**env, **change}, clear=True), patch('subprocess.run') as run:
                with self.assertRaises(SystemExit):
                    exec(step_python('release', 'Deploy existing verified candidate using SSH host keys'), {})
                run.assert_not_called()

    def test_deploy_dispatch_does_not_run_image_build_job(self):
        # 준비 / 실행 / 검증: 수동 배포가 이전에 격리 검증한 digest를 다시 만들지 않는다.
        self.assertFalse(permits('build_image', 'workflow_dispatch', 'refs/heads/main', 'deploy'))
        self.assertTrue(permits('release', 'workflow_dispatch', 'refs/heads/main', 'deploy'))
        self.assertEqual(re.findall(r'(?m)^    needs: (.+)$', job('release')), ['verify'])

    def test_candidate_revision_has_no_user_override_and_default_action_is_build_only(self):
        # 준비 / 실행 / 검증: candidate revision은 GITHUB_SHA뿐이며 임의 candidate_sha 입력이 없다.
        source = WORKFLOW.read_text()
        self.assertIn('        default: build-only', source)
        self.assertNotIn('candidate_sha', source)
        self.assertNotIn('RELEASE_SHA:', job('release'))
        self.assertNotIn('--sha', step_script('release', 'Deploy existing verified candidate using SSH host keys'))
        self.assertIn("os.environ['GITHUB_SHA']", step_script('release', 'Deploy existing verified candidate using SSH host keys'))

    def test_build_only_persists_exact_digest_and_git_sha_provenance(self):
        # 준비 / 실행 / 검증: artifact 생성만 mock write로 검사하고 runner/Docker는 실행하지 않는다.
        image = job('build_image')
        script = step_script('build_image', 'Build exact release SHA and push GHCR image')
        self.assertIn('image: ${{ steps.image.outputs.ref }}', image)
        self.assertIn('sha: ${{ steps.image.outputs.sha }}', image)
        self.assertIn('git rev-parse HEAD', script)
        self.assertIn('org.opencontainers.image.revision=$GITHUB_SHA', script)
        self.assertIn('^[0-9a-f]{64}$', script)
        self.assertIn("printf 'ref=%s\\nsha=%s\\n'", script)
        self.assertIn("REQUESTED_ACTION: ${{ inputs.action || 'build-only' }}", image)
        env = dict(IMAGE_REF=IMAGE, VERIFIED_SHA=SHA, GITHUB_REF='refs/heads/recovery-compatible',
                   REQUESTED_ACTION='build-only', GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2')
        with patch.dict(os.environ, env, clear=True), patch.object(Path, 'write_text', autospec=True) as write:
            exec(step_python('build_image', 'Build exact release SHA and push GHCR image'), {})
        self.assertEqual(write.call_args.args[0], Path('release-image.json'))
        self.assertEqual(json.loads(write.call_args.args[1]),
                         dict(schema_version=1, service='ll', image=IMAGE, git_sha=SHA,
                              source_ref=env['GITHUB_REF'], action='build-only', run_id='123', run_attempt='2'))
        self.assertIn('actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02', image)
        self.assertIn('path: release-image.json', image)
        self.assertIn('if-no-files-found: error', image)
        self.assertIn('retention-days: 30', image)


if __name__ == '__main__':
    unittest.main()
