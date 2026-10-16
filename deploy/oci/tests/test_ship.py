"""오프라인 전송 계약: SSH·OCI·Docker를 실행하거나 비밀정보를 출력하지 않는다."""
import importlib.util
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ship', HERE / 'ship.py')
ship = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ship)
SECRET = 'SYNTHETIC_SECRET_DO_NOT_PRINT'


class CommandClassificationTests(unittest.TestCase):
    def test_remote_command_nonzero_is_deployment_failure(self):
        # 준비: SSH 연결 후 원격 명령이 반환한 종료 코드를 재현한다.
        for exit_code in (1, 2, 127, 254):
            with self.subTest(exit_code=exit_code):
                result = subprocess.CompletedProcess(['ssh'], exit_code, SECRET, SECRET)
                with patch.object(ship.subprocess, 'run', return_value=result):
                    # 실행
                    with self.assertRaises(ship.RemoteDeploymentFailed) as caught:
                        ship.checked(['ssh', SECRET], remote_deployment=True)

                # 검증: 원격 실패에는 SSH 키 점검 안내를 붙이지 않는다.
                diagnostic = ship.release_failure_diagnostic(caught.exception)
                self.assertTrue(diagnostic.startswith('RELEASE_DEPLOYMENT_FAILED:'))
                self.assertNotIn('SSH', diagnostic)
                self.assertNotIn(SECRET, str(caught.exception))
                self.assertNotIn(SECRET, diagnostic)

    def test_ssh_255_and_local_signal_are_transport_failures(self):
        # 준비
        for exit_code in (255, -15):
            with self.subTest(exit_code=exit_code):
                result = subprocess.CompletedProcess(['ssh'], exit_code, SECRET, SECRET)
                with patch.object(ship.subprocess, 'run', return_value=result):
                    # 실행
                    with self.assertRaises(RuntimeError) as caught:
                        ship.checked(['ssh', SECRET], remote_deployment=True)

                # 검증
                self.assertNotIsInstance(caught.exception, ship.RemoteDeploymentFailed)
                diagnostic = ship.release_failure_diagnostic(caught.exception)
                self.assertTrue(diagnostic.startswith('RELEASE_TRANSPORT_FAILED:'))
                self.assertNotIn(SECRET, diagnostic)

    def test_setup_command_failure_remains_transport_failure(self):
        # 준비: scp 실패를 원격 배포 명령의 실패로 해석하지 않는다.
        result = subprocess.CompletedProcess(['scp'], 1, SECRET, SECRET)
        with patch.object(ship.subprocess, 'run', return_value=result):
            # 실행
            with self.assertRaises(RuntimeError) as caught:
                ship.checked(['scp', SECRET])

        # 검증
        self.assertNotIsInstance(caught.exception, ship.RemoteDeploymentFailed)
        self.assertTrue(ship.release_failure_diagnostic(caught.exception).startswith('RELEASE_TRANSPORT_FAILED:'))
        self.assertNotIn(SECRET, str(caught.exception))

    def test_execution_errors_never_render_exception_details(self):
        # 준비: subprocess 예외에는 인자와 부분 출력이 포함될 수 있다.
        errors = (
            subprocess.TimeoutExpired(['ssh', SECRET], 1, output=SECRET, stderr=SECRET),
            OSError(SECRET),
        )
        for error in errors:
            with self.subTest(error=type(error).__name__):
                with patch.object(ship.subprocess, 'run', side_effect=error):
                    # 실행
                    with self.assertRaises(type(error)) as caught:
                        ship.checked(['ssh', SECRET], remote_deployment=True)

                # 검증
                diagnostic = ship.release_failure_diagnostic(caught.exception)
                self.assertTrue(diagnostic.startswith('RELEASE_TRANSPORT_FAILED:'))
                self.assertNotIn(SECRET, diagnostic)

    def test_success_retains_capture_and_streaming_contract(self):
        # 준비
        result = subprocess.CompletedProcess(['ssh'], 0, 'safe fixture output', '')
        with patch.object(ship.subprocess, 'run', return_value=result) as run:
            # 실행
            captured = ship.checked(['ssh'], remote_deployment=True)
            streamed = ship.checked(['ssh'], show=True, remote_deployment=True)

        # 검증
        self.assertEqual(captured, 'safe fixture output')
        self.assertEqual(streamed, '')
        self.assertTrue(run.call_args_list[0].kwargs['capture_output'])
        self.assertFalse(run.call_args_list[1].kwargs['capture_output'])


class MainCleanupTests(unittest.TestCase):
    def test_remote_failure_preserves_secret_cleanup_and_agent_shutdown(self):
        # 준비: 실제 프로세스 대신 성공한 연결과 실패한 배포를 재현한다.
        environment = {
            'GITHUB_SHA': 'a' * 40,
            'GITHUB_RUN_ID': '123',
            'GITHUB_RUN_ATTEMPT': '1',
            'OCI_HOST': '203.0.113.10',
            'OCI_USERNAME': 'ubuntu',
            'RUNTIME_CONFIG_JSON': '{"service":"ll","public_url":"https://203.0.113.10:8443"}',
            'GITHUB_ACTOR': 'fixture-owner',
            'GHCR_TOKEN': SECRET,
            'SSH_KNOWN_HOSTS': 'fixture-host ssh-ed25519 fixture-public-key',
            'SSH_PRIVATE_KEY': SECRET,
        }
        calls = []

        def checked(argv, **kwargs):
            calls.append((argv, kwargs))
            if argv[0] == 'ssh-agent':
                return 'SSH_AUTH_SOCK=/fixture/socket; SSH_AGENT_PID=123;'
            if kwargs.get('remote_deployment'):
                raise ship.RemoteDeploymentFailed(SECRET)
            return ''

        with patch.dict(os.environ, environment, clear=True), \
                patch.object(ship.sys, 'argv', ['ship.py', '--service', 'll', '--image', 'ghcr.io/owner/ll@sha256:' + 'b' * 64]), \
                patch.object(ship, 'checked', side_effect=checked), \
                patch.object(ship.os, 'kill') as kill:
            # 실행
            with self.assertRaises(ship.RemoteDeploymentFailed) as caught:
                ship.main()

        # 검증: 배포 실패 뒤에도 원격 비밀 파일 삭제와 ssh-agent 종료를 시도한다.
        deployment = next(call for call in calls if call[1].get('remote_deployment'))
        self.assertTrue(deployment[1]['show'])
        self.assertEqual(deployment[1]['timeout'], 2400)
        cleanup = calls[-1]
        self.assertEqual(cleanup[0][0], 'ssh')
        self.assertIn('rm -f ', cleanup[0][-1])
        self.assertIn('/runtime.json ', cleanup[0][-1])
        self.assertIn('/registry-token', cleanup[0][-1])
        self.assertFalse(cleanup[1].get('remote_deployment', False))
        kill.assert_called_once_with(123, 15)
        self.assertNotIn(SECRET, ship.release_failure_diagnostic(caught.exception))


if __name__ == '__main__':
    unittest.main()
