package com.cloudcost.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 V13 이전에 평문으로 저장된 웹훅 주소를 암호화한다. 다음 저장을 기다리면 그동안 DB 사본에 평문 토큰이 남기 때문이다.
 * 키는 앱에만 있어 Flyway SQL로는 못 하고 여기서 한다. 이미 암호화된 행({@code v1:})은 건드리지 않아 여러 번 돌아도 같다.
 */
@Component
public class PlaintextWebhookEncryptor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaintextWebhookEncryptor.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    PlaintextWebhookEncryptor(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Encrypted {} plaintext webhook URL(s)", encryptPlaintext());
    }

    /** @return 암호화한 행 수 */
    public int encryptPlaintext() {
        List<Row> rows = jdbc.query(
            "SELECT id, webhook_url FROM notification_channels WHERE webhook_url NOT LIKE ?",
            (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("webhook_url")), SecretCipher.PREFIX + "%");
        int done = 0;
        for (Row r : rows) {
            // 조건부 UPDATE: 그 사이 사용자가 주소를 바꿨으면(이미 암호문) 덮어쓰지 않는다
            done += jdbc.update("UPDATE notification_channels SET webhook_url = ? WHERE id = ? AND webhook_url = ?",
                cipher.encrypt(r.url()), r.id(), r.url());
        }
        return done;
    }

    private record Row(UUID id, String url) {}
}
