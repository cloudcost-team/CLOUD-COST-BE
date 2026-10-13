package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.CustomerRolePolicy;
import com.cloudcost.domain.account.QuickCreateLink;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 고객용 CloudFormation 템플릿과 빠른 생성 링크가 보안 규칙·서로와 맞는지 확인한다. */
class QuickCreateTemplateTest {

    private static String template() throws IOException {
        try (InputStream in = QuickCreateTemplateTest.class.getResourceAsStream("/cloudformation/customer-readonly-role.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 템플릿은_허용된_두_액션만_주고_와일드카드가_없다() throws IOException {
        String yaml = template();
        Matcher actions = Pattern.compile("(?m)^\\s+- ([a-z0-9-]+:[A-Za-z*]+)\\s*$").matcher(yaml);
        List<String> found = actions.results().map(r -> r.group(1)).toList();
        assertThat(found).containsExactlyElementsOf(CustomerRolePolicy.ALLOWED_ACTIONS);
        assertThat(yaml).doesNotContain("ec2:*").doesNotContain("Describe*").doesNotContain("Action: \"*\"");

        List<String> allowlist = Files.readAllLines(Path.of("scripts/customer-role-allowlist.txt")).stream()
                .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        assertThat(allowlist).containsExactlyElementsOf(CustomerRolePolicy.ALLOWED_ACTIONS);
    }

    @Test
    void Trust_Policy에_ExternalId_조건과_백엔드_Principal이_있고_Role_이름은_CloudCost로_시작한다() throws IOException {
        String yaml = template();
        assertThat(yaml).contains("sts:ExternalId: !Ref ExternalId")
                .contains("AWS: !Ref TrustedPrincipalArn")
                .contains("AllowedPattern: \"^CloudCost")
                .contains("ServiceToken: !Ref CallbackTopicArn");
    }

    @Test
    void 링크가_넘기는_파라미터는_모두_템플릿에_있다() throws IOException {
        String yaml = template();
        AwsAccount account = AwsAccount.createPending(UUID.randomUUID(), "o", new AwsRegion("ap-northeast-2"), Instant.EPOCH);
        Map<String, String> params = QuickCreateLink.parameters(
                account, "arn:aws:iam::111122223333:role/CloudCostInsightBackendRole",
                "arn:aws:sns:ap-northeast-2:111122223333:cloudcost-insight-connections");
        params.keySet().forEach(name -> assertThat(yaml).contains("\n  " + name + ":"));
        assertThat(params.get("ExternalId")).matches("^cci-[0-9a-f]{24}$");
    }

    @Test
    void 스택_리전은_SNS_토픽_리전이다() {
        assertThat(QuickCreateLink.topicRegion("arn:aws:sns:us-east-1:111122223333:cci")).isEqualTo("us-east-1");
        assertThat(QuickCreateLink.topicRegion("")).isNull();
        assertThat(QuickCreateLink.build("https://b.s3.amazonaws.com/t.yaml", "us-east-1", Map.of("A", "x y")))
                .startsWith("https://us-east-1.console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/quickcreate?")
                .contains("templateURL=https%3A%2F%2Fb.s3.amazonaws.com%2Ft.yaml")
                .endsWith("&param_A=x+y");
    }
}
