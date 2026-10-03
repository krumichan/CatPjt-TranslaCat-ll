# LL OCI 배포

진입 workflow: `.github/workflows/deploy.yaml`.
인프라 1회 준비는 OCI 템플릿의 `deployment/README.md`를 따른다.
Secrets 이름/출처는 배포 묶음의 `LL_SECRETS.TXT`에 있다. 실제 Secrets 파일은 이 저장소에 커밋하지 않는다.

`runtime.example.json`은 예제이며 REPLACE 항목을 운영값으로 추정해 채우지 않았다.
DB TLS는 검토한 MySQL CA의 VERIFY_CA, 서버 간 HTTPS는 IP SAN이 있는 서비스 CA로 검증한다.
별도 migrations 계정을 사용하고 API는 `_app` DML 계정만 받는다.

LL은 Redis를 사용하지 않으며 생성하지 않는다. 오디오 named volume을 보존한다.

## 로컬 검증

```bash
python3 -m unittest discover -s deploy/oci/tests -v
docker build --platform linux/amd64 --target runtime -f deploy/oci/Dockerfile -t local/ll-verify .
```

Docker build에는 앱 컴파일/격리 테스트가 포함된다. 이 작성 환경에서는 Python 계약 테스트만 실제 실행했고 Docker/native build는 실행하지 못했다.
최초 PR verify를 통과하기 전 main 운영 배포를 승인하지 않는다.
Migration 이후 앱 rollback은 해당 변경의 backward compatibility를 별도 확인한 경우에만 허용한다. DB 역migration은 자동으로 하지 않는다.
