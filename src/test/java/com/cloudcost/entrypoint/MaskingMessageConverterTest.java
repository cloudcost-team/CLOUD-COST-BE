package com.cloudcost.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.entrypoint.logging.MaskingMessageConverter;
import org.junit.jupiter.api.Test;

class MaskingMessageConverterTest {

    @Test
    void 자격증명과_토큰을_로그에서_가린다() {
        String masked = MaskingMessageConverter.mask(
                "key=ASIAABCDEFGHIJKLMNOP SessionToken: abc123 SecretAccessKey=xyz "
                        + "Authorization: Bearer eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl");
        assertThat(masked)
                .doesNotContain("ASIAABCDEFGHIJKLMNOP")
                .doesNotContain("abc123")
                .doesNotContain("xyz")
                .doesNotContain("eyJhbGciOi")
                .contains("SessionToken: ***");
    }
}
