package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.CustomerRolePolicy;
import com.cloudcost.domain.account.ExternalId;
import com.cloudcost.domain.account.RoleArn;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountDomainTest {

    @Test
    void parsesRoleArnWithPath() {
        RoleArn arn = RoleArn.parse(" arn:aws:iam::123456789012:role/team/CloudCostReadOnlyRole ");
        assertThat(arn.accountId()).isEqualTo("123456789012");
        assertThat(arn.path()).isEqualTo("team/");
        assertThat(arn.roleName()).isEqualTo("CloudCostReadOnlyRole");
        assertThat(arn.value()).isEqualTo("arn:aws:iam::123456789012:role/team/CloudCostReadOnlyRole");
        assertThat(arn.toString()).doesNotContain("123456789012").contains("1234****9012");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "arn:aws:iam::12345678901:role/Short",
        "arn:aws-cn:iam::123456789012:role/OtherPartition",
        "arn:aws:iam::123456789012:user/NotARole",
        "arn:aws:sts::123456789012:assumed-role/Role/session"
    })
    void rejectsInvalidRoleArn(String raw) {
        assertThatThrownBy(() -> RoleArn.parse(raw))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Role ARN 형식");
    }

    @Test
    void externalIdIsRandomPerConnectionAndHasFixedFormat() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ExternalId id = ExternalId.generate();
            assertThat(id.value()).matches("^cci-[0-9a-f]{24}$");
            ids.add(id.value());
        }
        assertThat(ids).hasSize(200);
        assertThat(ExternalId.generate().toString()).doesNotMatch(".*[0-9a-f]{24}.*");
    }

    @Test
    void regionDefaultsToSeoulAndValidates() {
        assertThat(AwsRegion.ofNullable(null).value()).isEqualTo("ap-northeast-2");
        assertThat(AwsRegion.ofNullable("us-east-1").value()).isEqualTo("us-east-1");
        assertThatThrownBy(() -> AwsRegion.ofNullable("seoul")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void customerPolicyAllowsOnlyReadActionsWithoutWildcards() {
        Map<String, Object> policy = CustomerRolePolicy.permissionPolicy();
        Map<String, Object> statement = ((List<Map<String, Object>>) policy.get("Statement")).getFirst();
        assertThat((List<String>) statement.get("Action"))
                .containsExactly("ec2:DescribeInstances", "cloudwatch:GetMetricStatistics", "ec2:DescribeRegions",
                        "ec2:DescribeVolumes", "ec2:DescribeAddresses", "ec2:DescribeSnapshots",
                        "elasticloadbalancing:DescribeLoadBalancers")
                .noneMatch(a -> a.contains("*"))
                .allMatch(a -> a.contains(":Describe") || a.equals("cloudwatch:GetMetricStatistics"));
        assertThat(statement).as("잊힌 리전을 찾기 위해 리전 조건을 두지 않는다 (ADR-020)").doesNotContainKey("Condition");
    }

    @Test
    @SuppressWarnings("unchecked")
    void trustPolicyRequiresExternalIdAndBackendPrincipal() {
        ExternalId externalId = ExternalId.generate();
        Map<String, Object> trust = CustomerRolePolicy.trustPolicy(
                "arn:aws:iam::111122223333:role/CloudCostInsightBackendRole", externalId);
        Map<String, Object> statement = ((List<Map<String, Object>>) trust.get("Statement")).getFirst();
        assertThat(statement.get("Principal")).isEqualTo(Map.of("AWS", "arn:aws:iam::111122223333:role/CloudCostInsightBackendRole"));
        assertThat(statement.get("Action")).isEqualTo("sts:AssumeRole");
        assertThat(statement.get("Condition").toString()).contains("sts:ExternalId").contains(externalId.value());
    }
}
