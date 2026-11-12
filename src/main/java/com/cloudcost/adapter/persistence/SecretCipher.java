package com.cloudcost.adapter.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DB에 저장하는 사용자 비밀값(Discord 웹훅 주소)을 AES-256-GCM으로 암호화한다 (SECURITY.md, ADR-019).
 *
 * <ul>
 *   <li>키: {@code CCI_SECRET_KEY} (base64, 32바이트). DB에 두지 않으므로 DB만 유출돼서는 풀 수 없다
 *   <li>저장 형식: {@code v1:} + base64(IV 12바이트 + 암호문 + 인증 태그 16바이트). 값마다 무작위 IV라 같은 주소도 매번 다르게 저장된다
 *   <li>GCM 인증 태그로 위변조·다른 키를 감지한다 (복호화가 실패한다)
 *   <li>{@code v1:}이 없는 값은 암호화 전에 저장한 평문으로 보고 그대로 읽는다 (시작할 때 PlaintextWebhookEncryptor가 암호화한다)
 *   <li>AUTH_MODE=google(배포)에서 키가 없으면 시작하지 않는다. 로컬(dev)은 임시 키를 쓴다 (재시작하면 저장한 웹훅을 못 읽는다)
 * </ul>
 * 키·평문·암호문은 로그와 예외 메시지에 넣지 않는다.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    static final String PREFIX = "v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    SecretCipher(@Value("${cci.security.secret-key:}") String base64Key, @Value("${cci.auth.mode:dev}") String authMode) {
        if (base64Key == null || base64Key.isBlank()) {
            if (!"dev".equalsIgnoreCase(authMode)) {
                throw new IllegalStateException(
                    "AUTH_MODE=google에는 CCI_SECRET_KEY가 필요합니다 (openssl rand -base64 32로 만든 값).");
            }
            byte[] temp = new byte[KEY_BYTES];
            random.nextBytes(temp);
            this.key = new SecretKeySpec(temp, "AES");
            log.warn("CCI_SECRET_KEY가 없어 임시 암호화 키를 사용합니다. 재시작하면 저장한 웹훅을 읽지 못합니다 (로컬 전용).");
        } else {
            this.key = parse(base64Key);
        }
    }

    /** 테스트용: 키를 바로 넣는다 */
    public static SecretCipher of(String base64Key) {
        return new SecretCipher(base64Key, "google");
    }

    private static SecretKey parse(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CCI_SECRET_KEY는 base64 형식이어야 합니다.");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("CCI_SECRET_KEY는 32바이트(base64 44자)여야 합니다.");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("비밀값을 암호화하지 못했습니다.");
        }
    }

    /** {@code v1:}이 없으면 암호화 전 평문으로 보고 그대로 돌려준다 */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("저장된 비밀값 형식이 올바르지 않습니다.");
            }
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("저장된 비밀값을 풀지 못했습니다 (CCI_SECRET_KEY가 바뀌었거나 값이 손상됨).");
        }
    }
}
