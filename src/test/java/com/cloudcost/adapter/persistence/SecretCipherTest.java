package com.cloudcost.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretCipherTest {

    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    static final String OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    static final String URL = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz_-0123";

    @Test
    void 암호화한_값을_같은_키로_풀면_원래_값이고_매번_다르게_암호화된다() {
        SecretCipher cipher = SecretCipher.of(KEY);
        String a = cipher.encrypt(URL);
        String b = cipher.encrypt(URL);

        assertThat(a).startsWith("v1:").doesNotContain("abcdefghij").isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo(URL);
        assertThat(cipher.decrypt(b)).isEqualTo(URL);
        assertThat(a.length()).as("V13 칸(600자) 안에 들어간다").isLessThan(600);
    }

    @Test
    void 다른_키나_바뀐_값은_풀지_못하고_예외에_값을_담지_않는다() {
        String sealed = SecretCipher.of(KEY).encrypt(URL);
        assertThatThrownBy(() -> SecretCipher.of(OTHER_KEY).decrypt(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("discord");

        char last = sealed.charAt(sealed.length() - 2);
        String tampered = sealed.substring(0, sealed.length() - 2) + (last == 'A' ? 'B' : 'A') + sealed.charAt(sealed.length() - 1);
        assertThatThrownBy(() -> SecretCipher.of(KEY).decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 접두어가_없는_예전_평문은_그대로_돌려준다() {
        assertThat(SecretCipher.of(KEY).decrypt(URL)).isEqualTo(URL);
        assertThat(SecretCipher.of(KEY).decrypt(null)).isNull();
    }

    @Test
    void 배포_모드에서_키가_없거나_길이가_틀리면_시작하지_않는다() {
        assertThatThrownBy(() -> new SecretCipher("", "google"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CCI_SECRET_KEY");
        assertThatThrownBy(() -> SecretCipher.of("c2hvcnQ="))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32바이트");
        assertThatThrownBy(() -> SecretCipher.of("not base64 !!"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");

        SecretCipher local = new SecretCipher("", "dev");
        assertThat(local.decrypt(local.encrypt(URL))).as("로컬은 임시 키").isEqualTo(URL);
    }
}
