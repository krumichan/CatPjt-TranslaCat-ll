"""Offline contract tests: never contact OCI/MySQL/Redis/GitHub or a real Docker daemon."""
import base64, copy, hashlib, importlib.util, json, os, subprocess, tempfile, unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('deployment',HERE/'deploy.py')
d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
PEM='-----BEGIN CERTIFICATE-----\nOFFLINE_FIXTURE_NOT_A_TRUST_ANCHOR\n-----END CERTIFICATE-----\n'
SHA='a'*40;IMAGE='ghcr.io/owner/project@sha256:'+'b'*64;RID=SHA+'-123-1'
RECOVERY_SHA='c'*40;RECOVERY_IMAGE='ghcr.io/owner/project@sha256:'+'d'*64

def config(service='ll'):
    c=dict(schema_version=1,service=service,expected_vm_private_ip='10.0.1.10' if service=='ll' else '10.0.1.11',public_url='https://150.230.61.24:8443',timezone='UTC',
        database=dict(host='10.0.2.10',port=3306,name='translacat_'+service,username='translacat_'+service+'_app',
            password='Runtime_Only!$literal',migration_username='translacat_'+service+'_migrator',migration_password='Migration_Only!$literal'),
        ai_url='https://161.33.34.50:8443',ai_api_key='independent-opaque-key',mysql_ca_pem=PEM,service_ca_pem=PEM,
        expected_mysql_ca_sha256=hashlib.sha256(PEM.encode()).hexdigest(),allow_app_rollback_after_migration=False,registry_user='owner')
    if service=='ll': c.update(internal_jwt_key_base64=base64.b64encode(b'a'*32).decode(),tts_model='approved-model',audio_base_url=c['public_url'])
    else: c.update(be_url='https://217.142.230.186:8443',browser_origin='https://frontend.example',
        user_jwt_key_base64=base64.b64encode(b'a'*32).decode(),ingress_key_base64=base64.b64encode(b'b'*32).decode(),
        identity_key_base64=base64.b64encode(b'c'*32).decode(),
        redis=dict(container_name='translacat-chat-redis',network='translacat-chat-private',user='chat',namespace='translacat:chat:Production',password='d'*64))
    return c

class ValidationTests(unittest.TestCase):
    def rejected(self,change,service='ll'):
        c=config(service);change(c)
        with self.assertRaises((d.DeployError,ValueError)): d.validate(c,service)
    def test_valid_ll(self): self.assertEqual(d.validate(config(),'ll')['service'],'ll')
    def test_valid_chat(self): self.assertEqual(d.validate(config('chat'),'chat')['service'],'chat')
    def test_wrong_database(self): self.rejected(lambda c:c['database'].update(name='mysql'))
    def test_wrong_runtime_user(self): self.rejected(lambda c:c['database'].update(username='dbadmin'))
    def test_wrong_migration_user(self): self.rejected(lambda c:c['database'].update(migration_username='dbadmin'))
    def test_same_password_allowed_for_distinct_accounts(self):
        c=config();c['database']['migration_password']=c['database']['password']
        validated=d.validate(c,'ll')
        self.assertNotEqual(validated['database']['username'],validated['database']['migration_username'])
    def test_placeholder_blocked(self): self.rejected(lambda c:c.update(ai_api_key='REPLACE_KEY'))
    def test_newline_blocked(self): self.rejected(lambda c:c['database'].update(password='valid\nINJECT=value'))
    def test_http_outbound_blocked(self): self.rejected(lambda c:c.update(ai_url='http://161.33.34.50:8000'))
    def test_public_port_fixed(self): self.rejected(lambda c:c.update(public_url='https://150.230.61.24:443'))
    def test_credentials_in_url_blocked(self): self.rejected(lambda c:c.update(ai_url='https://user:secret@161.33.34.50:8443'))
    def test_ca_hash_not_auto_trusted(self): self.rejected(lambda c:c.update(expected_mysql_ca_sha256='0'*64))
    def test_no_private_key_in_ca(self): self.rejected(lambda c:c.update(service_ca_pem=PEM+'PRIVATE KEY'))
    def test_ll_redis_rejected(self): self.rejected(lambda c:c.update(redis={}))
    def test_chat_key_directions(self): self.rejected(lambda c:c.update(identity_key_base64=c['ingress_key_base64']),'chat')
    def test_short_jwt_blocked(self): self.rejected(lambda c:c.update(internal_jwt_key_base64=base64.b64encode(b'abc').decode()))
    def test_redis_password_shape(self): self.rejected(lambda c:c['redis'].update(password='weak'),'chat')
    def test_timezone_no_absolute_path(self): self.rejected(lambda c:c.update(timezone='/etc/passwd'))
    def test_no_tls_skip_arguments(self):
        for service in ('ll','chat'):
            c=config(service);args=d.base_args(c,Path('/tmp/config'),'test',IMAGE)
            self.assertNotIn('--privileged',args);self.assertIn('--read-only',args)
            self.assertIn('127.0.0.1:8081:8081' if service=='ll' else '127.0.0.1:5085:8080',args)
    def test_connection_password_quoted_as_data(self):
        c=config('chat');c['database']['password']='Semi;colon"$notexecuted'
        s=d.connection(c)
        self.assertIn('Password="Semi;colon""$notexecuted"',s);self.assertIn('SslMode="VerifyCA"',s)
    def test_environment_not_shell_code(self):
        self.assertEqual(d.env_text({'VALUE':'$(touch /tmp/should-not-exist)\"x'}),'VALUE=$(touch /tmp/should-not-exist)\"x\n')
    def test_materialize_ll_credentials_separated(self):
        with tempfile.TemporaryDirectory() as td,patch.object(d.os,'geteuid',return_value=1000):
            c=config();r=d.materialize(c,Path(td));m=d.materialize(c,Path(td),True)
            runtime=(r/'runtime.env').read_text();migration=(m/'runtime.env').read_text()
            self.assertIn('DB_MIGRATION_MODE=validate',runtime);self.assertNotIn(c['database']['migration_password'],runtime)
            self.assertIn('DB_MIGRATION_MODE=migrate',migration);self.assertNotIn(c['ai_api_key'],migration)
            self.assertEqual((r/'runtime.env').stat().st_mode & 0o777,0o600)
            self.assertEqual(r.stat().st_mode & 0o777,0o700)
    def test_materialize_chat_credentials_separated(self):
        with tempfile.TemporaryDirectory() as td,patch.object(d.os,'geteuid',return_value=1000):
            c=config('chat');r=d.materialize(c,Path(td));m=d.materialize(c,Path(td),True)
            self.assertIn(c['database']['password'],(r/'db_connection').read_text())
            self.assertNotIn(c['database']['migration_password'],(r/'db_connection').read_text())
            self.assertFalse((m/'user_jwt').exists());self.assertFalse((m/'redis_password').exists())
            self.assertIn('SSL_CERT_FILE=/run/config/ca-bundle.pem',(r/'runtime.env').read_text())

class RedisTests(unittest.TestCase):
    def existing(self,running=True):
        return {'Config':{'Image':'redis:7.4.10-alpine','Labels':{}},'HostConfig':{'NetworkMode':'private','PortBindings':{}},
            'NetworkSettings':{'Networks':{'translacat-chat-private':{}}},'State':{'Running':running}}
    def test_running_preserved(self):
        with patch.object(d,'inspect',return_value=self.existing()),patch.object(d,'network'),patch.object(d,'run',return_value='PONG') as run:
            d.ensure_redis(config('chat'),Path('/not-used'))
            self.assertEqual(len(run.call_args_list),1)
            self.assertEqual(run.call_args.args[0][:3],['docker','exec','-i'])
            self.assertNotIn('d'*64,' '.join(run.call_args.args[0]))
    def test_stopped_started_not_recreated(self):
        with patch.object(d,'inspect',return_value=self.existing(False)),patch.object(d,'network'),patch.object(d,'run',return_value='PONG') as run:
            d.ensure_redis(config('chat'),Path('/not-used'))
            cmds=[x.args[0] for x in run.call_args_list]
            self.assertEqual(cmds[0],['docker','start','translacat-chat-redis']);self.assertFalse(any('rm' in x for x in cmds))
    def test_exposed_redis_rejected(self):
        r=self.existing();r['HostConfig']['PortBindings']={'6379/tcp':[{}]}
        with patch.object(d,'inspect',return_value=r), self.assertRaises(d.DeployError): d.ensure_redis(config('chat'),Path('/not-used'))
    def test_wrong_password_not_reset(self):
        with patch.object(d,'inspect',return_value=self.existing()),patch.object(d,'network'),patch.object(d.time,'sleep'),patch.object(d,'run',return_value='NOAUTH') as run:
            with self.assertRaises(d.DeployError): d.ensure_redis(config('chat'),Path('/not-used'))
            self.assertTrue(all(x.args[0][1]=='exec' for x in run.call_args_list))
    def test_old_compose_name_blocks_second_cache(self):
        with patch.object(d,'inspect',return_value=None),patch.object(d,'run',return_value='old-chat-redis') as run:
            with self.assertRaises(d.DeployError): d.ensure_redis(config('chat'),Path('/not-used'))
            self.assertEqual(run.call_count,1)
    def test_new_redis_private_persistent_no_argv_password(self):
        with tempfile.TemporaryDirectory() as td,patch.object(d,'inspect',return_value=None),patch.object(d,'network'),patch.object(d,'volume'),patch.object(d.os,'chown'),patch.object(d,'run') as run:
            run.side_effect=lambda args,**kw:'PONG' if args[1]=='exec' else ''
            c=config('chat');d.ensure_redis(c,Path(td));calls=[x.args[0] for x in run.call_args_list]
            create=next(x for x in calls if x[1]=='run')
            self.assertNotIn('-p',create);self.assertNotIn('--publish',create)
            self.assertIn('type=volume,src=translacat-chat-redis-data,dst=/data',create)
            self.assertNotIn(c['redis']['password'],(Path(td)/'redis/users.acl').read_text())
            self.assertIn('appendonly yes',(Path(td)/'redis/redis.conf').read_text())

class FakeDocker:
    def __init__(self,migration_exit='0'):
        self.commands=[];self.migration_exit=migration_exit
        self.objects={'translacat-ll':{'Id':'previous','Config':{'Labels':{'tc.owner':d.OWNER}},'State':{'Running':True}}}
    def inspect(self,kind,name):
        if kind=='image':
            recovery=name==RECOVERY_IMAGE
            return {'Id':'sha256:'+('d' if recovery else 'b')*64,'Config':{'Labels':{
                'org.opencontainers.image.revision':RECOVERY_SHA if recovery else SHA,
                'io.translacat.ll.database-validation':'strict-v1'}}}
        value=self.objects.get(name) or next((v for v in self.objects.values() if v.get('Id')==name),None)
        return copy.deepcopy(value)
    def run(self,args,**kwargs):
        args=[str(x) for x in args];self.commands.append(args)
        if args[0]=='curl': return getattr(self,'public_response','{"status":"READY"}\n200')
        op=args[1]
        if op=='create':
            n=args[args.index('--name')+1];self.objects[n]={'Id':'new-'+n,'Config':{'Labels':{'tc.owner':d.OWNER}},'State':{'Running':False}}
            return self.objects[n]['Id']
        elif op in ('start','stop','rm'):
            target=args[2] if op=='start' else args[-1]
            name=target if target in self.objects else next((k for k,v in self.objects.items() if v.get('Id')==target),target)
            if op=='rm': self.objects.pop(name,None)
            else: self.objects[name]['State']['Running']=op=='start'
        elif op=='rename': self.objects[args[3]]=self.objects.pop(args[2])
        elif op=='wait': return getattr(self,'validation_exit','0') if '-recovery-validation-' in args[2] else self.migration_exit
        return ''

class ReleaseTests(unittest.TestCase):
    def exercise(self,db_exit='0',ready=True,rollback=False):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);token=root/'token';token.write_text('short-lived-fixture-token');backend=FakeDocker(db_exit);c=config();c['allow_app_rollback_after_migration']=rollback
            with patch.object(d,'ROOT',root),patch.object(d,'run',side_effect=backend.run),patch.object(d,'inspect',side_effect=backend.inspect),\
                 patch.object(d,'network'),patch.object(d,'volume'),patch.object(d,'external_preflight'),patch.object(d,'assert_vm_role'),patch.object(d,'wait_ready',return_value=ready),patch.object(d.os,'geteuid',return_value=0),patch.object(d.os,'chown'):
                error=None
                try: d.deploy(c,IMAGE,SHA,RID,token)
                except d.DeployError as e: error=str(e)
            self.assertFalse(token.exists());self.assertFalse((root/'ll/releases'/RID/'migration').exists())
            return backend,error
    def test_failed_migration_keeps_previous_running(self):
        b,e=self.exercise(db_exit='1');self.assertIn('MIGRATION_FAILED',e)
        self.assertTrue(b.objects['translacat-ll']['State']['Running']);self.assertFalse(any(x[1]=='stop' and x[-1]=='translacat-ll' for x in b.commands))
    def test_readiness_failure_without_schema_approval_preserves_old_stopped(self):
        b,e=self.exercise(ready=False)
        self.assertIn('APPLICATION_READINESS_FAILED',e)
        old=next(v for v in b.objects.values() if v['Id']=='previous');self.assertFalse(old['State']['Running'])
    def test_legacy_boolean_is_not_ll_migration_compatibility_proof(self):
        # 준비 / 실행: legacy bool만 켜고 migration 이후 readiness를 실패시킨다.
        b,e=self.exercise(ready=False,rollback=True)

        # 검증: future migration을 모르는 구 LL을 재시작하지 않는다.
        self.assertIn('APPLICATION_READINESS_FAILED',e)
        old=next(v for v in b.objects.values() if v['Id']=='previous')
        self.assertFalse(old['State']['Running'])
        self.assertEqual(sum(x[:3]==['docker','start','translacat-ll'] for x in b.commands),1)
    def test_success_keeps_previous_for_recovery(self):
        b,e=self.exercise();self.assertIsNone(e);self.assertTrue(b.objects['translacat-ll']['State']['Running'])
        self.assertTrue(any(v['Id']=='previous' for v in b.objects.values()))
    def test_migration_precedes_stopping_app(self):
        b,e=self.exercise();wait=next(i for i,x in enumerate(b.commands) if x[1]=='wait');stop=next(i for i,x in enumerate(b.commands) if x[1]=='stop')
        self.assertLess(wait,stop)
    def test_mutable_image_ref_rejected(self):
        with tempfile.TemporaryDirectory() as td,patch.object(d.os,'geteuid',return_value=0):
            with self.assertRaises(d.DeployError): d.deploy(config(),'image:latest',SHA,RID,Path(td)/'token')

    def test_failed_ll_migration_emits_only_allowlisted_diagnostic(self):
        safe=['LL_MIGRATION_FAILED: PoolInitializationException',
            'LL_MIGRATION_CAUSE_1: CommunicationsException','LL_MIGRATION_SQLSTATE_1: 08S01']
        with patch.object(d,'migration_failure_diagnostics',return_value=safe) as diagnostic,\
             patch('builtins.print') as output:
            self.exercise(db_exit='1')
        diagnostic.assert_called_once()
        rendered=[' '.join(str(a) for a in call.args) for call in output.call_args_list]
        for line in safe: self.assertIn(line,rendered)

    def test_connection_failure_is_not_reported_as_schema_or_ssh_failure(self):
        # 준비: 실제 배포에서 보고된 기존 이미지 진단을 사용한다.
        safe=['LL_MIGRATION_FAILED: PoolInitializationException',
              'LL_MIGRATION_CAUSE_1: CommunicationsException',
              'LL_MIGRATION_SQLSTATE_1: 08S01',
              'LL_MIGRATION_SQLCODE_1: 0',
              'LL_MIGRATION_CAUSE_2: CJCommunicationsException',
              'LL_MIGRATION_CAUSE_3: SocketException']

        # 실행: 실패한 migration 이후 기존 앱 상태까지 확인한다.
        with patch.object(d,'migration_failure_diagnostics',return_value=safe),patch('builtins.print'):
            backend,error=self.exercise(db_exit='1')

        # 검증: 연결 실패로 분류하고 기존 앱은 실행 상태로 보존한다.
        self.assertEqual(error,'MIGRATION_FAILED:DATABASE_CONNECTION_FAILED;NO_DATABASE_ROLLBACK')
        self.assertTrue(backend.objects['translacat-ll']['State']['Running'])

    def test_migration_failure_categories_are_fixed_codes(self):
        # 준비 / 실행 / 검증: 상세 진단의 고정 분류를 원문 메시지 없이 전달한다.
        for category,reason in [
            ('TIMEOUT','DATABASE_CONNECTION_TIMEOUT'),
            ('TLS','DATABASE_TLS_FAILED'),
            ('AUTHENTICATION','DATABASE_AUTHENTICATION_FAILED'),
            ('SCHEMA_OR_MIGRATION','CHECK_SCHEMA_OR_MIGRATION'),
            ('UNKNOWN','CHECK_MIGRATION_DIAGNOSTICS'),
        ]:
            with self.subTest(category=category):
                self.assertEqual(d.migration_failure_reason([
                    'LL_MIGRATION_CATEGORY: '+category,
                    'LL_MIGRATION_SQLSTATE_1: 08S01',
                ]),reason)

    def test_legacy_tls_and_authentication_diagnostics(self):
        # 준비 / 실행 / 검증: 새 category가 없는 이미지에서도 TLS를 통신 오류보다 우선한다.
        self.assertEqual(d.migration_failure_reason([
            'LL_MIGRATION_SQLSTATE_1: 08S01',
            'LL_MIGRATION_CAUSE_3: SSLHandshakeException',
        ]),'DATABASE_TLS_FAILED')
        self.assertEqual(d.migration_failure_reason([
            'LL_MIGRATION_SQLSTATE_0: 28000',
            'LL_MIGRATION_SQLCODE_0: 1045',
        ]),'DATABASE_AUTHENTICATION_FAILED')
        self.assertEqual(d.migration_failure_reason([
            'LL_MIGRATION_CATEGORY: password=DO_NOT_PRINT',
        ]),'CHECK_MIGRATION_DIAGNOSTICS')

class PublicReadinessTests(unittest.TestCase):
    def test_exact_200_ready_accepts_and_keeps_tls_hostname_and_no_redirect_contract(self):
        # 준비 / 실행: body와 curl이 별도 수집한 마지막 HTTP 코드를 함께 전달한다.
        with patch.object(d,'run',return_value='{"status":"READY"}\n200') as run:
            d.public_ready(config(),Path('/owned/runtime'))

        # 검증: CA/hostname 검증을 유지하고 사용자 curl 설정이나 redirect를 적용하지 않는다.
        args=run.call_args.args[0]
        self.assertEqual(args[:2],['curl','--disable'])
        self.assertEqual(args[args.index('--noproxy')+1],'*')
        self.assertIn('--write-out',args)
        self.assertEqual(args[args.index('--write-out')+1],'\n%{http_code}')
        self.assertEqual(args[args.index('--cacert')+1],'/owned/runtime/service-ca.pem')
        self.assertEqual(args[args.index('--resolve')+1],'150.230.61.24:8443:127.0.0.1')
        self.assertEqual(args[-1],'https://150.230.61.24:8443/health/ready')
        self.assertFalse({'-k','--insecure','-L','--location'} & set(args))

    def test_redirects_never_count_as_ready_even_with_ready_json(self):
        # 준비 / 실행 / 검증: --fail이 거절하지 않는 3xx도 정확한 200이 아니면 실패한다.
        for status in ('301','302','307','308'):
            with self.subTest(status=status),patch.object(d,'run',return_value='{"status":"READY"}\n'+status):
                with self.assertRaises(d.DeployError): d.public_ready(config(),Path('/runtime'))

    def test_200_html_or_nonready_json_is_rejected_without_echoing_body(self):
        bodies=['<html>SECRET_EDGE_BODY</html>','{"status":"STARTING","secret":"SECRET_EDGE_BODY"}',
                '{"status":"ready"}','{}','null','[]','"READY"','{"status":true}',
                '{broken','{"status":"READY"} {"second":"document"}']
        for body in bodies:
            with self.subTest(body=body),patch.object(d,'run',return_value=body+'\n200'):
                with self.assertRaises(d.DeployError) as failure: d.public_ready(config(),Path('/runtime'))
                self.assertNotIn('SECRET_EDGE_BODY',str(failure.exception))
                self.assertNotIn(body,str(failure.exception))

    def test_missing_or_non200_status_cannot_be_inferred_from_body(self):
        for response in ('{"status":"READY"}','{"status":"READY"}\n000','{"status":"READY"}\n204',
                         '{"status":"READY"}\n401','{"status":"READY"}\n500'):
            with self.subTest(response=response),patch.object(d,'run',return_value=response):
                with self.assertRaises(d.DeployError): d.public_ready(config(),Path('/runtime'))

    def test_curl_transport_or_tls_failure_stays_failure_with_safe_diagnostic(self):
        with patch.object(d,'run',side_effect=d.DeployError('COMMAND_FAILED:curl:exit=60')):
            with self.assertRaises(d.DeployError) as failure: d.public_ready(config(),Path('/runtime'))
        self.assertEqual(str(failure.exception),'PUBLIC_HTTPS_READINESS_TRANSPORT_FAILED')


class RecoveryTests(unittest.TestCase):
    def exercise(self, *, validation_exit='0', proof='41', ready=(False,True), db_exit='0', image_labels=None, public_response=None):
        # 준비: Docker/DB/네트워크 대신 상태를 가진 독립 fake를 사용한다.
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);token=root/'token';token.write_text('synthetic-only')
            backend=FakeDocker(db_exit);backend.validation_exit=validation_exit
            if public_response is not None: backend.public_response=public_response
            inspect=backend.inspect
            def image_inspect(kind,name):
                value=inspect(kind,name)
                if image_labels is not None and kind=='image' and name==RECOVERY_IMAGE:
                    value['Config']['Labels']=image_labels
                return value
            with patch.object(d,'ROOT',root),patch.object(d,'run',side_effect=backend.run), \
                 patch.object(d,'inspect',side_effect=image_inspect),patch.object(d,'network'),patch.object(d,'volume'), \
                 patch.object(d,'external_preflight'),patch.object(d,'assert_vm_role'), \
                 patch.object(d,'wait_ready',side_effect=ready),patch.object(d,'validation_schema',return_value=proof), \
                 patch.object(d.os,'geteuid',return_value=0),patch.object(d.os,'chown'):
                # 실행: 원래 release 실패는 복구 성공 후에도 호출자에게 전파된다.
                error=None
                try:
                    d.deploy(config(),IMAGE,SHA,RID,token,recovery_image=RECOVERY_IMAGE,recovery_sha=RECOVERY_SHA)
                except d.DeployError as failure:
                    error=str(failure)
            receipt=json.loads((root/'ll/releases'/RID/'result.json').read_text())
            self.assertFalse(token.exists())
            self.assertFalse((root/'ll/releases'/RID/'migration').exists())
            return backend,error,receipt

    def test_selected_compatible_image_validates_then_recovers_without_claiming_release_success(self):
        # 준비 / 실행
        backend,error,receipt=self.exercise()

        # 검증: 필수 원래 실패, 실제 선택 이미지, DB 역이행 없음, 보존된 구 컨테이너.
        self.assertEqual(error,'APPLICATION_READINESS_FAILED')
        self.assertEqual(receipt['status'],'DEPLOYMENT_FAILED')
        self.assertEqual(receipt['recovery_status'],'RECOVERY_READY')
        self.assertEqual(receipt['recovery_schema_version'],'41')
        self.assertEqual(receipt['database_rollback'],'NEVER_AUTOMATIC')
        self.assertEqual(receipt['migration_state'],'COMPLETED')
        self.assertEqual(receipt['business_e2e'],'NOT_RUN')
        self.assertTrue(backend.objects['translacat-ll']['State']['Running'])
        self.assertFalse(next(v for v in backend.objects.values() if v['Id']=='previous')['State']['Running'])
        creates=[x for x in backend.commands if x[1]=='create']
        validation=next(x for x in creates if x[-1]=='--validate-database')
        self.assertEqual(validation[-2],RECOVERY_IMAGE)
        self.assertIn('/runtime,dst=/run/config,readonly',' '.join(validation))
        self.assertNotIn('-p',validation);self.assertNotIn('--restart',validation)
        self.assertNotIn('dst=/app/data',' '.join(validation))
        self.assertNotIn('--migrate-database',validation)
        self.assertEqual(creates[-1][-1],RECOVERY_IMAGE)
        wait=next(i for i,x in enumerate(backend.commands) if x[1]=='wait' and '-recovery-validation-' in x[2])
        self.assertLess(wait,backend.commands.index(creates[-1]))
        self.assertEqual(sum(x[0]=='curl' for x in backend.commands),1)

    def test_bad_https_edge_never_marks_candidate_or_recovery_ready(self):
        # 준비 / 실행: localhost는 둘 다 READY지만 edge는 READY 본문을 가진 302다.
        backend,error,receipt=self.exercise(ready=(True,True),public_response='{"status":"READY"}\n302')

        # 검증: 후보와 복구 모두 실패하고 원래 배포 실패·수동 복구 판정을 유지한다.
        self.assertEqual(error,'PUBLIC_HTTPS_READINESS_HTTP_STATUS_NOT_200')
        self.assertEqual(receipt['status'],'DEPLOYMENT_FAILED')
        self.assertEqual(receipt['recovery_status'],'FAILED_MANUAL_RECOVERY_REQUIRED')
        self.assertEqual(receipt['recovery_failure_code'],'PUBLIC_HTTPS_READINESS_HTTP_STATUS_NOT_200')
        self.assertEqual(sum(x[0]=='curl' for x in backend.commands),2)
        self.assertNotIn('translacat-ll',backend.objects)

    def test_invalid_current_schema_never_starts_recovery_api(self):
        backend,error,receipt=self.exercise(validation_exit='1',ready=(False,))
        self.assertEqual(error,'APPLICATION_READINESS_FAILED')
        self.assertEqual(receipt['recovery_status'],'FAILED_MANUAL_RECOVERY_REQUIRED')
        self.assertEqual(receipt['recovery_failure_code'],'RECOVERY_DATABASE_VALIDATION_FAILED')
        self.assertFalse(any(x[1]=='create' and x[-1]==RECOVERY_IMAGE for x in backend.commands))
        self.assertNotIn('translacat-ll',backend.objects)
        self.assertFalse(any('-recovery-validation-' in name for name in backend.objects))

    def test_recovery_runtime_failure_stays_failed_and_is_preserved(self):
        backend,error,receipt=self.exercise(ready=(False,False))
        self.assertEqual(error,'APPLICATION_READINESS_FAILED')
        self.assertEqual(receipt['recovery_status'],'FAILED_MANUAL_RECOVERY_REQUIRED')
        self.assertEqual(receipt['recovery_failure_code'],'RECOVERY_APPLICATION_NOT_READY')
        self.assertNotIn('translacat-ll',backend.objects)
        self.assertTrue(any('-recovery-failed-' in name for name in backend.objects))

    def test_missing_entrypoint_capability_aborts_before_any_migration(self):
        backend,error,receipt=self.exercise(image_labels={'org.opencontainers.image.revision':RECOVERY_SHA},ready=())
        self.assertEqual(error,'RECOVERY_VALIDATION_ENTRYPOINT_REQUIRED')
        self.assertEqual(receipt['migration_state'],'NOT_STARTED')
        self.assertFalse(any(x[1]=='create' for x in backend.commands))
        self.assertTrue(backend.objects['translacat-ll']['State']['Running'])

    def test_partial_migration_does_not_restart_or_replace_running_original(self):
        backend,error,receipt=self.exercise(db_exit='1',ready=())
        self.assertIn('MIGRATION_FAILED',error)
        self.assertEqual(receipt['migration_state'],'ATTEMPTED_UNKNOWN')
        self.assertEqual(receipt['recovery_status'],'EXISTING_STATE_PRESERVED_SCHEMA_REVIEW_REQUIRED')
        self.assertTrue(backend.objects['translacat-ll']['State']['Running'])
        self.assertFalse(any(x[:3]==['docker','start','translacat-ll'] for x in backend.commands))
        self.assertFalse(any(x[1]=='create' and x[-1]=='--validate-database' for x in backend.commands))

    def test_chat_explicit_legacy_rollback_contract_is_preserved(self):
        # 준비: LL 전용 제한이 기존 CHAT 복구 계약을 바꾸지 않는지 확인한다.
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);token=root/'token';token.write_text('synthetic-only')
            backend=FakeDocker();previous=backend.objects.pop('translacat-ll')
            backend.objects['translacat-chat']=previous
            settings=config('chat');settings['allow_app_rollback_after_migration']=True
            with patch.object(d,'ROOT',root),patch.object(d,'run',side_effect=backend.run), \
                 patch.object(d,'inspect',side_effect=backend.inspect),patch.object(d,'network'),patch.object(d,'ensure_redis'), \
                 patch.object(d,'external_preflight'),patch.object(d,'assert_vm_role'),patch.object(d,'wait_ready',side_effect=[False,True]), \
                 patch.object(d.os,'geteuid',return_value=0),patch.object(d.os,'chown'):
                # 실행
                with self.assertRaises(d.DeployError): d.deploy(settings,IMAGE,SHA,RID,token)

            # 검증: 기존 명시적 CHAT 정책만 그대로 적용한다. LL recovery pair는 지원하지 않는다.
            self.assertEqual(backend.objects['translacat-chat']['Id'],'previous')
            self.assertTrue(backend.objects['translacat-chat']['State']['Running'])
            self.assertEqual(json.loads((root/'chat/releases'/RID/'result.json').read_text())['recovery_status'],'PREVIOUS_READY')

    def test_existing_validation_container_is_never_started_or_removed(self):
        # 준비: 같은 이름은 소유 증거가 아니다.
        foreign={'Id':'foreign-existing','Config':{'Labels':{}},'State':{'Running':True}}
        with patch.object(d,'inspect',return_value=foreign),patch.object(d,'run') as run:
            # 실행 / 검증: create 이전 거절하며 foreign 컨테이너에는 명령을 보내지 않는다.
            with self.assertRaisesRegex(d.DeployError,'RECOVERY_VALIDATION_CONTAINER_NAME_OCCUPIED'):
                d.recover_application(config(),Path('/runtime'),'translacat-ll',RECOVERY_IMAGE,'collision','suffix')
            run.assert_not_called()

    def test_existing_migration_name_is_preserved_and_never_treated_as_our_resource(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);token=root/'token';token.write_text('synthetic-only')
            backend=FakeDocker();name='translacat-ll-migration-'+RID[-25:]
            foreign={'Id':'foreign-existing','Config':{'Labels':{}},'State':{'Running':True}}
            backend.objects[name]=foreign
            with patch.object(d,'ROOT',root),patch.object(d,'run',side_effect=backend.run), \
                 patch.object(d,'inspect',side_effect=backend.inspect),patch.object(d,'network'),patch.object(d,'volume'), \
                 patch.object(d,'external_preflight'),patch.object(d,'assert_vm_role'), \
                 patch.object(d.os,'geteuid',return_value=0),patch.object(d.os,'chown'):
                with self.assertRaisesRegex(d.DeployError,'MIGRATION_CONTAINER_NAME_OCCUPIED'):
                    d.deploy(config(),IMAGE,SHA,RID,token)
            self.assertEqual(backend.objects[name],foreign)
            self.assertFalse(any(x[1] in ('start','stop','rm') and (name in x or 'foreign-existing' in x) for x in backend.commands))
            self.assertEqual(json.loads((root/'ll/releases'/RID/'result.json').read_text())['migration_state'],'NOT_STARTED')

    def test_recovery_pair_fails_closed_before_mutation(self):
        for image,sha in [(RECOVERY_IMAGE,None),(None,RECOVERY_SHA),('image:latest',RECOVERY_SHA),
                          (RECOVERY_IMAGE,'bad'),(IMAGE,RECOVERY_SHA),(RECOVERY_IMAGE,SHA)]:
            with self.subTest(image=image,sha=sha),self.assertRaises(d.DeployError):
                d.recovery_selection('ll',IMAGE,SHA,image,sha)
        with self.assertRaises(d.DeployError):
            d.recovery_selection('chat',IMAGE,SHA,RECOVERY_IMAGE,RECOVERY_SHA)
        d.recovery_selection('ll',IMAGE,SHA,RECOVERY_IMAGE,RECOVERY_SHA)

    def test_result_write_failure_cannot_skip_secret_cleanup_or_hide_original_failure(self):
        # 준비: 상태 파일 저장만 실패하게 하고 Docker/DB는 fake로 유지한다.
        for ready in (True,False):
            with self.subTest(ready=ready),tempfile.TemporaryDirectory() as td:
                root=Path(td);token=root/'token';token.write_text('synthetic-only')
                backend=FakeDocker();write_text=Path.write_text
                def failed_result(path,*args,**kwargs):
                    if path.name=='result.json': raise OSError('synthetic disk full')
                    return write_text(path,*args,**kwargs)
                with patch.object(d,'ROOT',root),patch.object(d,'run',side_effect=backend.run), \
                     patch.object(d,'inspect',side_effect=backend.inspect),patch.object(d,'network'),patch.object(d,'volume'), \
                     patch.object(d,'external_preflight'),patch.object(d,'assert_vm_role'),patch.object(d,'wait_ready',return_value=ready), \
                     patch.object(d.os,'geteuid',return_value=0),patch.object(d.os,'chown'),patch.object(Path,'write_text',failed_result):
                    # 실행 / 검증: 성공 후 저장 실패와 원래 readiness 실패를 구별한다.
                    with self.assertRaises(d.DeployError) as failure:
                        d.deploy(config(),IMAGE,SHA,RID,token)
                self.assertEqual(str(failure.exception),'RELEASE_RESULT_WRITE_FAILED' if ready else 'APPLICATION_READINESS_FAILED')
                self.assertFalse(token.exists())
                self.assertFalse((root/'ll/releases'/RID/'migration').exists())
                self.assertFalse((root/'ll/releases'/RID/'registry').exists())

    def test_validation_requires_unique_success_schema_and_zero_executed_proof(self):
        valid='LL_DATABASE_VALIDATION_COMPLETE\nLL_DATABASE_SCHEMA_VERSION: 41\nLL_DATABASE_MIGRATIONS_EXECUTED: 0\n'
        for output in [valid,valid.replace('VERSION: 41','VERSION: 041'),valid+'LL_DATABASE_VALIDATION_COMPLETE\n',valid.replace('EXECUTED: 0','EXECUTED: 1'),
                       valid.replace('VERSION: 41','VERSION: unknown'),'password=DO_NOT_PRINT']:
            result=subprocess.CompletedProcess(['docker','logs'],0,output,'secret=DO_NOT_PRINT')
            with patch.object(d.subprocess,'run',return_value=result):
                if output in (valid,valid.replace('VERSION: 41','VERSION: 041')): self.assertEqual(d.validation_schema('owned-validation'),'41')
                else:
                    with self.assertRaises(d.DeployError) as error: d.validation_schema('owned-validation')
                    self.assertNotIn('DO_NOT_PRINT',str(error.exception))


class SourceContracts(unittest.TestCase):
    def test_migration_diagnostic_does_not_forward_raw_logs(self):
        raw=('password=DO_NOT_PRINT\nLL_MIGRATION_FAILED: PoolInitializationException\n'
            'LL_MIGRATION_CAUSE_1: SQLException\nLL_MIGRATION_SQLSTATE_1: 28000\n'
            'LL_MIGRATION_SQLCODE_1: 1045\njdbc:mysql://secret.internal\n'
            'LL_MIGRATION_CAUSE_9: ShouldNotPass\nLL_MIGRATION_SQLSTATE_2: TOO_LONG')
        result=subprocess.CompletedProcess(['docker','logs'],1,stdout=raw,stderr='token=DO_NOT_PRINT')
        with patch.object(d.subprocess,'run',return_value=result):
            self.assertEqual(d.migration_failure_diagnostics('migration'),[
                'LL_MIGRATION_FAILED: PoolInitializationException',
                'LL_MIGRATION_CAUSE_1: SQLException',
                'LL_MIGRATION_SQLSTATE_1: 28000',
                'LL_MIGRATION_SQLCODE_1: 1045',
            ])

    def test_socket_reason_and_category_are_allowlisted_without_raw_messages(self):
        # 준비: 올바른 고정 코드와 비밀값을 포함한 변형을 섞는다.
        raw='\n'.join([
            'LL_MIGRATION_CATEGORY: CONNECTION',
            'LL_MIGRATION_SQLSTATE_0: 08S01',
            'LL_MIGRATION_SQLCODE_0: 0',
            'LL_MIGRATION_SOCKET_REASON_3: RESET',
            'LL_MIGRATION_CATEGORY: SECRET_VALUE',
            'LL_MIGRATION_SOCKET_REASON_3: RESET password=DO_NOT_PRINT',
            'LL_MIGRATION_SOCKET_REASON_9: RESET',
            'LL_MIGRATION_SOCKET_REASON_0: private-db.internal',
        ])
        result=subprocess.CompletedProcess(['docker','logs'],1,stdout=raw,stderr='')

        # 실행 / 검증: 허용한 enum과 인덱스만 통과한다.
        with patch.object(d.subprocess,'run',return_value=result):
            self.assertEqual(d.migration_failure_diagnostics('migration'),[
                'LL_MIGRATION_CATEGORY: CONNECTION',
                'LL_MIGRATION_SQLSTATE_0: 08S01',
                'LL_MIGRATION_SQLCODE_0: 0',
                'LL_MIGRATION_SOCKET_REASON_3: RESET',
            ])

    def test_entrypoint_no_eval(self):
        s=(HERE/'entrypoint.sh').read_text();self.assertNotIn('eval ',s);self.assertNotIn('source /run',s)
    def test_transport_strict_host_and_no_agent_forwarding(self):
        s=(HERE/'ship.py').read_text();self.assertIn('StrictHostKeyChecking yes',s);self.assertIn('ForwardAgent no',s)
        self.assertNotIn('StrictHostKeyChecking no',s)
    def test_no_unbounded_stop_or_prune_redis(self):
        s=(HERE/'deploy.py').read_text();self.assertNotIn('system prune',s);self.assertNotIn('volume prune',s);self.assertNotIn('flushall',s.lower().replace('no flushall',''))
    def test_shell_syntax(self): subprocess.run(['sh','-n',str(HERE/'entrypoint.sh')],check=True)

if __name__=='__main__': unittest.main()
