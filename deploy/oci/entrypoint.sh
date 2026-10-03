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
if [ "${1:-}" = "--migrate-database" ]; then
    main=jp.co.translacat.languagelearning.bootstrap.MigrationMainKt
    shift
else
    main=io.ktor.server.netty.EngineMain
    set -- -config=/app/config/application.yaml -config=/app/config/application-prod.yaml "$@"
fi
exec java -Xms64m -Xmx320m -XX:MaxMetaspaceSize=144m -XX:MaxDirectMemorySize=64m \
    -XX:ActiveProcessorCount=1 -Duser.timezone="${TZ:?TZ is required}" \
    -Djavax.net.ssl.trustStore=/tmp/service-cacerts -Djavax.net.ssl.trustStorePassword=changeit \
    -cp '/app/lib/*' "$main" "$@"
