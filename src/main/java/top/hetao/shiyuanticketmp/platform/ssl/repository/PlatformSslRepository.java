package top.hetao.shiyuanticketmp.platform.ssl.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.platform.ssl.certificate.CertificateBundle;
import top.hetao.shiyuanticketmp.platform.ssl.crypto.EncryptedPayload;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PlatformSslRepository {

    private static final RowMapper<PlatformSslState> STATE_MAPPER = (rs, rowNum) -> new PlatformSslState(
            rs.getBoolean("desired_enabled"), rs.getBoolean("effective_enabled"),
            rs.getObject("current_certificate_version_id", Long.class), rs.getString("domain_name"),
            rs.getString("challenge_type"), rs.getBoolean("legacy_import_completed"), rs.getString("last_error"));

    private static final RowMapper<StoredCertificate> CERTIFICATE_MAPPER = (rs, rowNum) -> new StoredCertificate(
            rs.getLong("id"), rs.getBytes("encrypted_pkcs12"), rs.getBytes("encryption_nonce"),
            rs.getString("fingerprint_sha256"), rs.getString("subject_dn"), rs.getString("sans_json"),
            rs.getTimestamp("not_before").toLocalDateTime(), rs.getTimestamp("not_after").toLocalDateTime(),
            rs.getString("key_algorithm"), rs.getString("source"), rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PlatformSslRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean tablesExist() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'platform_ssl_config'
                """, Integer.class);
        return count != null && count == 1;
    }

    public Optional<PlatformSslState> findState() {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM platform_ssl_config WHERE id = 1", STATE_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public StoredCertificate requireCertificate(long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM platform_ssl_certificate_version WHERE id = ?", CERTIFICATE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("TLS certificate version " + id + " does not exist", e);
        }
    }

    public long insertCertificate(CertificateBundle bundle, EncryptedPayload encrypted, String source, Long actorId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO platform_ssl_certificate_version
                      (encrypted_pkcs12, encryption_nonce, fingerprint_sha256, subject_dn, sans_json,
                       not_before, not_after, key_algorithm, source, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'STAGED', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setBytes(1, encrypted.ciphertext());
            statement.setBytes(2, encrypted.nonce());
            statement.setString(3, bundle.fingerprintSha256());
            statement.setString(4, bundle.subjectDn());
            statement.setString(5, toJson(bundle.subjectAlternativeNames()));
            statement.setTimestamp(6, Timestamp.valueOf(bundle.notBefore()));
            statement.setTimestamp(7, Timestamp.valueOf(bundle.notAfter()));
            statement.setString(8, bundle.keyAlgorithm());
            statement.setString(9, source);
            if (actorId == null) statement.setObject(10, null); else statement.setLong(10, actorId);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a certificate version ID");
        return key.longValue();
    }

    public long insertOperation(String type, boolean fromHttps, boolean toHttps,
                                Long certificateId, Long operatorId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO platform_ssl_operation
                      (operation_type, status, from_https, to_https, certificate_version_id, operator_id)
                    VALUES (?, 'PENDING', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, type);
            statement.setBoolean(2, fromHttps);
            statement.setBoolean(3, toHttps);
            statement.setObject(4, certificateId);
            statement.setObject(5, operatorId);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an operation ID");
        return key.longValue();
    }

    public void completeOperation(long id, boolean succeeded, String message) {
        jdbcTemplate.update("""
                UPDATE platform_ssl_operation
                SET status = ?, message = ?, completed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, succeeded ? "SUCCEEDED" : "FAILED", truncate(message), id);
    }

    public void updateState(boolean desired, boolean effective, Long certificateId,
                            String domain, String lastError, Long actorId) {
        jdbcTemplate.update("""
                UPDATE platform_ssl_config
                SET desired_enabled = ?, effective_enabled = ?, current_certificate_version_id = ?,
                    domain_name = ?, last_error = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = 1
                """, desired, effective, certificateId, blankToNull(domain), truncate(lastError), actorId);
    }

    @Transactional
    public void activateCertificate(long id) {
        jdbcTemplate.update("UPDATE platform_ssl_certificate_version SET status = 'SUPERSEDED' WHERE status = 'ACTIVE'");
        jdbcTemplate.update("UPDATE platform_ssl_certificate_version SET status = 'ACTIVE' WHERE id = ?", id);
    }

    public void failCertificate(long id) {
        jdbcTemplate.update("UPDATE platform_ssl_certificate_version SET status = 'FAILED' WHERE id = ?", id);
    }

    @Transactional
    public void restoreCertificate(Long previousId, long failedId) {
        failCertificate(failedId);
        if (previousId != null) {
            jdbcTemplate.update(
                    "UPDATE platform_ssl_certificate_version SET status = 'ACTIVE' WHERE id = ?", previousId);
        }
    }

    public void markLegacyImported() {
        jdbcTemplate.update("UPDATE platform_ssl_config SET legacy_import_completed = 1 WHERE id = 1");
    }

    public void updateLegacyImported(boolean imported) {
        jdbcTemplate.update(
                "UPDATE platform_ssl_config SET legacy_import_completed = ? WHERE id = 1", imported);
    }

    public long insertDeployToken(String name, byte[] tokenHash, LocalDateTime expiresAt, long actorId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO platform_ssl_deploy_token (name, token_hash, expires_at, created_by)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setBytes(2, tokenHash);
            statement.setTimestamp(3, Timestamp.valueOf(expiresAt));
            statement.setLong(4, actorId);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a deploy token ID");
        return key.longValue();
    }

    public boolean consumeDeployToken(byte[] tokenHash) {
        return jdbcTemplate.update("""
                UPDATE platform_ssl_deploy_token
                SET last_used_at = CURRENT_TIMESTAMP
                WHERE token_hash = ? AND expires_at > CURRENT_TIMESTAMP AND revoked_at IS NULL
                """, tokenHash) == 1;
    }

    public List<PlatformSslOperationView> recentOperations(int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM platform_ssl_operation ORDER BY id DESC LIMIT ?
                """, (rs, rowNum) -> new PlatformSslOperationView(
                rs.getLong("id"), rs.getString("operation_type"), rs.getString("status"),
                rs.getBoolean("from_https"), rs.getBoolean("to_https"),
                rs.getObject("certificate_version_id", Long.class), rs.getString("message"),
                rs.getObject("operator_id", Long.class), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime()), limit);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize certificate SANs", e);
        }
    }

    private String truncate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
