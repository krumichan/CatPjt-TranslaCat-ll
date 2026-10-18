#!/bin/sh
set -eu
while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] || continue
    name=${line%%=*}
    case "$name" in *[!A-Za-z0-9_]*|'') echo 'INVALID_ENV_NAME' >&2; exit 2;; esac
    export "$line"
done < /run/config/runtime.env
# 공개 루트 CA는 유지하고 서비스 전용 CA를 추가한다. DB용 저장소에는 DB CA만 넣는다.
cp "$JAVA_HOME/lib/security/cacerts" /tmp/service-cacerts
keytool -importcert -noprompt -alias translacat-service -file /run/config/service-ca.pem \
    -keystore /tmp/service-cacerts -storepass changeit >/dev/null 2>&1
keytool -importcert -noprompt -alias translacat-mysql -file /run/config/mysql-ca.pem \
    -keystore /tmp/mysql-trust.p12 -storetype PKCS12 -storepass changeit >/dev/null 2>&1
case "${1:-}" in
    --migrate-database)
        if [ "$#" -ne 1 ]; then
            echo 'INVALID_DATABASE_COMMAND' >&2
            exit 2
        fi
        # 기존 이행 진입점은 인자를 제거한다. 추가 인자는 MigrationMain에서 연결 전에 거절한다.
        main=jp.co.translacat.languagelearning.bootstrap.MigrationMainKt
        shift
        ;;
    --validate-database)
        if [ "$#" -ne 1 ]; then
            echo 'INVALID_DATABASE_COMMAND' >&2
            exit 2
        fi
        # 읽기 전용 검증 인자를 그대로 전달한다. Ktor와 작업자를 시작하지 않는다.
        main=jp.co.translacat.languagelearning.bootstrap.MigrationMainKt
        ;;
    --validate*|--migrate*)
        echo 'INVALID_DATABASE_COMMAND' >&2
        exit 2
        ;;
    *)
        main=io.ktor.server.netty.EngineMain
        set -- -config=/app/config/application.yaml -config=/app/config/application-prod.yaml "$@"
        ;;
esac
exec java -Xms64m -Xmx320m -XX:MaxMetaspaceSize=144m -XX:MaxDirectMemorySize=64m \
    -XX:ActiveProcessorCount=1 -Duser.timezone="${TZ:?TZ is required}" \
    -Djavax.net.ssl.trustStore=/tmp/service-cacerts -Djavax.net.ssl.trustStorePassword=changeit \
    -cp '/app/lib/*' "$main" "$@"
