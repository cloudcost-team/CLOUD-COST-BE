package com.cloudcost.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;

/** 헥사고날 의존 규칙과 보안 규칙을 코드 구조로 강제한다. */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.cloudcost");
    }

    @Test
    void domain은_JDK와_domain에만_의존한다_Spring_AWS_JPA_Jackson_금지() {
        classes().that().resideInAPackage("com.cloudcost.domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "com.cloudcost.domain..")
                .check(classes);
    }

    @Test
    void application은_AWS_SDK와_JPA_Spring_Web에_직접_의존하지_않는다() {
        noClasses().that().resideInAPackage("com.cloudcost.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "software.amazon.awssdk..", "jakarta.persistence..", "org.springframework.web..",
                        "org.springframework.data..", "com.fasterxml.jackson..",
                        "com.cloudcost.adapter..", "com.cloudcost.entrypoint..")
                .check(classes);
    }

    @Test
    void AWS_SDK는_adapter에서만_쓴다() {
        noClasses().that().resideOutsideOfPackage("com.cloudcost.adapter..")
                .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk..")
                .check(classes);
    }

    @Test
    void 고객_계정_어댑터와_우리_계정_어댑터는_서로_import하지_않는다() {
        noClasses().that().resideInAPackage("com.cloudcost.adapter.awscustomer..")
                .should().dependOnClassesThat().resideInAPackage("com.cloudcost.adapter.awsinternal..")
                .check(classes);
        noClasses().that().resideInAPackage("com.cloudcost.adapter.awsinternal..")
                .should().dependOnClassesThat().resideInAPackage("com.cloudcost.adapter.awscustomer..")
                .check(classes);
    }

    @Test
    void 고객_계정에는_허용된_읽기_API만_호출한다() {
        Set<String> clients = Set.of(
                "software.amazon.awssdk.services.ec2.Ec2Client",
                "software.amazon.awssdk.services.cloudwatch.CloudWatchClient",
                "software.amazon.awssdk.services.sts.StsClient",
                "software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client");
        // 허용 목록(scripts/customer-role-allowlist.txt)의 읽기 API만 (ADR-020)
        Set<String> allowed = Set.of("describeInstances", "getMetricStatistics", "describeRegions", "describeVolumes",
                "describeAddresses", "describeSnapshots", "describeLoadBalancers", "builder", "close");
        DescribedPredicate<JavaMethodCall> forbiddenCall = new DescribedPredicate<>(
                "a customer AWS API outside the read-only allowlist") {
            @Override
            public boolean test(JavaMethodCall call) {
                return clients.contains(call.getTargetOwner().getName()) && !allowed.contains(call.getName());
            }
        };
        noClasses().that().resideInAPackage("com.cloudcost.adapter.awscustomer..")
                .should().callMethodWhere(forbiddenCall)
                .check(classes);
        noClasses().that().resideInAPackage("com.cloudcost.adapter.awscustomer..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "software.amazon.awssdk.services.costexplorer..",
                        "software.amazon.awssdk.services.computeoptimizer..",
                        "software.amazon.awssdk.services.pricing..")
                .check(classes);
    }

    @Test
    void Repository는_owner_조건_없는_findById_findAll을_갖지_않는다() {
        classes().that().areAssignableTo(Repository.class)
                .should().notBeAssignableTo(CrudRepository.class)
                .andShould().notBeAssignableTo(ListCrudRepository.class)
                .andShould().notBeAssignableTo(PagingAndSortingRepository.class)
                .check(classes);
        methods().that().areDeclaredInClassesThat().areAssignableTo(Repository.class)
                .should().haveNameNotMatching("findById|findAll|findAllById|getById|getReferenceById")
                .check(classes);
    }

    @Test
    void 저장_엔티티에_자격증명_컬럼이_없다() {
        Pattern forbidden = Pattern.compile("(?i).*(secret|accesskey|sessiontoken|credential|password).*");
        fields().that().areDeclaredInClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().haveNameNotMatching(forbidden.pattern())
                .check(classes);
    }
}
