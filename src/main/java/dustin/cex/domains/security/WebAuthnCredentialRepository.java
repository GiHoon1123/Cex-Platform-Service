package dustin.cex.domains.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.webauthn.api.*;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/**
 * PostgreSQL의 공개키 저장만 담당한다. 서명·CBOR·인증서 검증을 직접 구현하지 않는다.
 * 표준 JDBC 구현의 빈 전송수단 문자열 복원 문제를 피하되 같은 표와 타입 계약을 사용한다.
 * 호출하는 서비스가 사용자 행을 먼저 잠그므로 같은 사람의 카운터 갱신도 직렬화된다.
 */
final class WebAuthnCredentialRepository implements UserCredentialRepository {
    private final JdbcTemplate jdbc;
    WebAuthnCredentialRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public CredentialRecord findByCredentialId(Bytes id) {
        var rows=jdbc.query("SELECT * FROM user_credentials WHERE credential_id=?",this::read,id.toBase64UrlString());
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Override public List<CredentialRecord> findByUserId(Bytes id) {
        return jdbc.query("SELECT * FROM user_credentials WHERE user_entity_user_id=?",this::read,id.toBase64UrlString());
    }
    @Override public void delete(Bytes id) { jdbc.update("DELETE FROM user_credentials WHERE credential_id=?",id.toBase64UrlString()); }
    @Override public void save(CredentialRecord r) {
        // 검증된 공개키는 최초 등록에만 기록하고 인증 시에는 카운터·최근 사용 시각만 바꾼다.
        // 기존 키를 다른 사용자에게 덮어쓰는 경로는 제공하지 않는다.
        if(findByCredentialId(r.getCredentialId())!=null) {
            jdbc.update("UPDATE user_credentials SET signature_count=?,last_used=? WHERE credential_id=? AND user_entity_user_id=?",
                r.getSignatureCount(),Timestamp.from(r.getLastUsed()),r.getCredentialId().toBase64UrlString(),r.getUserEntityUserId().toBase64UrlString());
            return;
        }
        jdbc.update("""
            INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,
             backup_eligible,authenticator_transports,public_key_credential_type,backup_state,attestation_object,
             attestation_client_data_json,created,last_used,label) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,r.getCredentialId().toBase64UrlString(),r.getUserEntityUserId().toBase64UrlString(),r.getPublicKey().getBytes(),
            r.getSignatureCount(),r.isUvInitialized(),r.isBackupEligible(),
            String.join(",",r.getTransports().stream().map(AuthenticatorTransport::getValue).sorted().toList()),
            r.getCredentialType().getValue(),r.isBackupState(),r.getAttestationObject().getBytes(),
            r.getAttestationClientDataJSON().getBytes(),Timestamp.from(r.getCreated()),Timestamp.from(r.getLastUsed()),r.getLabel());
    }
    private CredentialRecord read(ResultSet rs,int row) throws SQLException {
        Set<AuthenticatorTransport> transports=new HashSet<>();
        // 브라우저가 전송수단을 생략한 경우 빈 집합은 유효하다. 임의로 USB나 내장 기기로 추측하지 않는다.
        for(String value:rs.getString("authenticator_transports").split(","))
            if(!value.isEmpty()) transports.add(AuthenticatorTransport.valueOf(value));
        return ImmutableCredentialRecord.builder().credentialId(Bytes.fromBase64(rs.getString("credential_id")))
            .userEntityUserId(Bytes.fromBase64(rs.getString("user_entity_user_id")))
            .publicKey(new ImmutablePublicKeyCose(rs.getBytes("public_key"))).signatureCount(rs.getLong("signature_count"))
            .uvInitialized(rs.getBoolean("uv_initialized")).backupEligible(rs.getBoolean("backup_eligible"))
            .backupState(rs.getBoolean("backup_state")).credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .attestationObject(new Bytes(rs.getBytes("attestation_object")))
            .attestationClientDataJSON(new Bytes(rs.getBytes("attestation_client_data_json")))
            .created(rs.getTimestamp("created").toInstant()).lastUsed(rs.getTimestamp("last_used").toInstant())
            .label(rs.getString("label")).transports(transports).build();
    }
}
