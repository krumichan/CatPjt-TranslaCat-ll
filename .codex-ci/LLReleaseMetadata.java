import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** 展開済み LL の実行権限で Flyway 履歴の比較用メタデータだけを取得する。 */
public final class LLReleaseMetadata {
    private static final String CATALOG = "translacat_ll";
    private static final String ACCOUNT = "translacat_ll_app";
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    private static final String DRIVER_JAR = "mysql-connector-j-9.7.0.jar";
    private static final Path TRUST_STORE = Path.of("/tmp/mysql-trust.p12");
    private static final int EXPECTED_VERSION = 42;
    private static final int MAX_ROWS = 256;

    private static final String TLS_QUERY =
            "sslMode=VERIFY_CA&trustCertificateKeyStoreUrl=file:/tmp/mysql-trust.p12"
                    + "&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=changeit"
                    + "&fallbackToSystemTrustStore=false";
    private static final String IPV4_PART = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern URL = Pattern.compile(
            "jdbc:mysql://" + IPV4_PART + "(?:\\." + IPV4_PART + "){3}:3306/"
                    + CATALOG + "\\?" + Pattern.quote(TLS_QUERY)
    );
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}");
    private static final String HISTORY_SQL = """
            SELECT /*+ MAX_EXECUTION_TIME(5000) */
                   installed_rank, version, success, checksum
            FROM translacat_ll.flyway_schema_history
            ORDER BY installed_rank ASC
            LIMIT 257
            """;

    private record Credentials(String jdbcUrl, String username, String password) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    private record History(int rows, int versionedRows, int lastRank, int maxVersion, String sha256) {
    }

    private static final class InvalidInput extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class InvalidRuntime extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class InvalidHistory extends Exception {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 引数を受け取らず、標準入力の Base64 3 行から読み取る。
     * 成功時は固定形式の JSON 1 行、異常時は固定エラーコードだけを出力する。
     *
     * @param args 空でなければ入力エラー。
     */
    public static void main(String[] args) {
        try {
            // ドライバーの初期化や接続より前に入力と信頼ストアの境界を確認する。
            if (args.length != 0) {
                throw new InvalidInput();
            }
            Credentials credentials = readCredentials(new BufferedInputStream(System.in));
            requireRuntime(credentials.jdbcUrl());

            // 接続が閉じられた後だけ比較用の成功結果を返す。
            History history = readHistory(credentials);
            System.out.printf(
                    Locale.ROOT,
                    "{\"ok\":true,\"formatVersion\":1,\"catalog\":\"translacat_ll\","
                            + "\"schemaVersion\":%d,\"rowCount\":%d,\"versionedRowCount\":%d,"
                            + "\"lastInstalledRank\":%d,\"historySha256\":\"%s\"}%n",
                    history.maxVersion(), history.rows(), history.versionedRows(),
                    history.lastRank(), history.sha256()
            );
        } catch (InvalidInput | IOException error) {
            fail(64, "LL_METADATA_INVALID_INPUT");
        } catch (InvalidRuntime error) {
            fail(65, "LL_METADATA_INVALID_RUNTIME");
        } catch (SQLException error) {
            fail(66, "LL_METADATA_QUERY_FAILED");
        } catch (InvalidHistory error) {
            fail(67, "LL_METADATA_UNEXPECTED_HISTORY");
        } catch (Exception | LinkageError error) {
            fail(70, "LL_METADATA_INTERNAL_ERROR");
        }
    }

    // URL のオプションは既存 runtime の固定形式だけを許可し、認証情報を別に渡す。
    private static Credentials readCredentials(InputStream input) throws IOException, InvalidInput {
        String url = readLine(input, 768);
        String user = readLine(input, 64);
        String password = readLine(input, 1024);
        if (input.read() != -1 || !URL.matcher(url).matches() || !ACCOUNT.equals(user)) {
            throw new InvalidInput();
        }
        return new Credentials(url, user, password);
    }

    // 標準 Base64、厳密な UTF-8、改行 1 個を要求する。値の空白は変更しない。
    private static String readLine(InputStream input, int maxDecodedBytes) throws IOException, InvalidInput {
        int maxEncodedBytes = ((maxDecodedBytes + 2) / 3) * 4;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != '\n') {
            if (next < 0 || next > 0x7f || line.size() == maxEncodedBytes) {
                throw new InvalidInput();
            }
            line.write(next);
        }
        byte[] encoded = line.toByteArray();
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length == 0 || decoded.length > maxDecodedBytes
                    || !Arrays.equals(Base64.getEncoder().encode(decoded), encoded)) {
                throw new InvalidInput();
            }
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decoded)).toString();
            if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0) {
                throw new InvalidInput();
            }
            return text;
        } catch (IllegalArgumentException error) {
            throw new InvalidInput();
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    // 実行環境の既存 trust store と、配布済み Connector/J のファイル名を確認する。
    private static void requireRuntime(String jdbcUrl) throws InvalidRuntime {
        try {
            if (Runtime.version().feature() != 21
                    || !Files.isRegularFile(TRUST_STORE) || !Files.isReadable(TRUST_STORE)) {
                throw new InvalidRuntime();
            }
            Class.forName(DRIVER_CLASS);
            Driver driver = DriverManager.getDriver(jdbcUrl);
            Path driverJar = Path.of(driver.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!driver.getClass().getName().equals(DRIVER_CLASS)
                    || !DRIVER_JAR.equals(driverJar.getFileName().toString())) {
                throw new InvalidRuntime();
            }
        } catch (Exception | LinkageError error) {
            throw new InvalidRuntime();
        }
    }

    // 明示的なサーバー側 read-only セッションを使い、固定 SELECT の後にロールバックする。
    private static History readHistory(Credentials credentials) throws Exception {
        Properties settings = new Properties();
        settings.setProperty("user", credentials.username());
        settings.setProperty("password", credentials.password());
        settings.setProperty("connectTimeout", "5000");
        settings.setProperty("socketTimeout", "8000");
        settings.setProperty("autoReconnect", "false");
        settings.setProperty("allowMultiQueries", "false");
        settings.setProperty("readOnlyPropagatesToServer", "true");
        settings.setProperty("logger", "com.mysql.cj.log.NullLogger");
        settings.setProperty("paranoid", "true");

        try (Connection connection = DriverManager.getConnection(credentials.jdbcUrl(), settings)) {
            settings.clear();
            if (!CATALOG.equals(connection.getCatalog())) {
                throw new InvalidRuntime();
            }
            connection.setReadOnly(true);
            if (!connection.isReadOnly()) {
                throw new InvalidRuntime();
            }
            connection.setAutoCommit(false);
            try (Statement query = connection.createStatement();
                    ResultSet rows = query.executeQuery(HISTORY_SQL)) {
                return summarize(rows);
            } finally {
                connection.rollback();
            }
        } finally {
            settings.clear();
        }
    }

    // 各行を rank 順の型付きバイト列にし、値や例外の詳細を出力せず全行の hash を返す。
    private static History summarize(ResultSet rows) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int count = 0;
        int versionedCount = 0;
        int previousRank = -1;
        int maxVersion = 0;

        try (DataOutputStream canonical =
                new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            canonical.writeUTF("TRANSLACAT_LL_FLYWAY_METADATA_V1");
            while (rows.next()) {
                int rank = rows.getInt(1);
                if (rows.wasNull() || rank <= previousRank || ++count > MAX_ROWS) {
                    throw new InvalidHistory();
                }
                String version = rows.getString(2);
                boolean success = rows.getBoolean(3);
                if (rows.wasNull() || !success) {
                    throw new InvalidHistory();
                }
                int checksum = rows.getInt(4);
                boolean hasChecksum = !rows.wasNull();

                if (version != null) {
                    if (!VERSION.matcher(version).matches()) {
                        throw new InvalidHistory();
                    }
                    maxVersion = Math.max(maxVersion, Integer.parseInt(version));
                    versionedCount++;
                }
                canonical.writeInt(rank);
                canonical.writeBoolean(version != null);
                if (version != null) {
                    canonical.writeUTF(version);
                }
                canonical.writeBoolean(success);
                canonical.writeBoolean(hasChecksum);
                if (hasChecksum) {
                    canonical.writeInt(checksum);
                }
                previousRank = rank;
            }
            canonical.writeInt(count);
        }

        if (versionedCount == 0 || maxVersion != EXPECTED_VERSION) {
            throw new InvalidHistory();
        }
        return new History(count, versionedCount, previousRank, maxVersion, HexFormat.of().formatHex(digest.digest()));
    }

    // 接続情報・SQL 例外・生の行データを含まない固定コードで終了する。
    private static void fail(int exitCode, String errorCode) {
        System.out.println("{\"ok\":false,\"error\":\"" + errorCode + "\"}");
        System.exit(exitCode);
    }
}
