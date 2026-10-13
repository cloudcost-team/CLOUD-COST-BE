package com.cloudcost.domain.cost;

import com.cloudcost.domain.ec2.Ec2Instance;
import java.util.Map;
import java.util.Optional;

/**
 * AWS Price List(AmazonEC2) 온디맨드 단가 조회 조건. Price List 속성 이름(operatingSystem, tenancy, licenseModel, preInstalledSw)의 값을
 * 그대로 쓴다.
 */
public record PriceQuery(
    String region,
    String instanceType,
    String operatingSystem,
    String tenancy,
    String licenseModel,
    String preInstalledSw) {

    private static final String NO_LICENSE = "No License required";
    private static final String BYOL = "Bring your own license";

    private record Os(String operatingSystem, String licenseModel, String preInstalledSw) {
    }

    /**
     * DescribeInstances platformDetails → Price List 속성. 목록에 없는 OS는 단가를 조회하지 않는다.
     */
    private static final Map<String, Os> PLATFORMS = Map.ofEntries(
        Map.entry("Linux/UNIX", new Os("Linux", NO_LICENSE, "NA")),
        Map.entry("Red Hat Enterprise Linux", new Os("RHEL", NO_LICENSE, "NA")),
        Map.entry("Red Hat Enterprise Linux with HA", new Os("Red Hat Enterprise Linux with HA", NO_LICENSE, "NA")),
        Map.entry("Red Hat BYOL Linux", new Os("RHEL", BYOL, "NA")),
        Map.entry("SUSE Linux", new Os("SUSE", NO_LICENSE, "NA")),
        Map.entry("Ubuntu Pro", new Os("Ubuntu Pro", NO_LICENSE, "NA")),
        Map.entry("Windows", new Os("Windows", NO_LICENSE, "NA")),
        Map.entry("Windows BYOL", new Os("Windows", BYOL, "NA")),
        Map.entry("Windows with SQL Server Standard", new Os("Windows", NO_LICENSE, "SQL Std")),
        Map.entry("Windows with SQL Server Web", new Os("Windows", NO_LICENSE, "SQL Web")),
        Map.entry("Windows with SQL Server Enterprise", new Os("Windows", NO_LICENSE, "SQL Ent")),
        Map.entry("Linux with SQL Server Standard", new Os("Linux", NO_LICENSE, "SQL Std")),
        Map.entry("Linux with SQL Server Web", new Os("Linux", NO_LICENSE, "SQL Web")),
        Map.entry("Linux with SQL Server Enterprise", new Os("Linux", NO_LICENSE, "SQL Ent")));

    private static final Map<String, String> TENANCY =
        Map.of("default", "Shared", "dedicated", "Dedicated", "host", "Host");

    public static Optional<PriceQuery> forInstance(Ec2Instance instance) {
        String platform = instance.platformDetails() == null ? "Linux/UNIX" : instance.platformDetails();
        Os os = PLATFORMS.get(platform);
        String tenancy = TENANCY.get(instance.tenancy() == null ? "default" : instance.tenancy());
        if (os == null || tenancy == null || instance.instanceType() == null) {
            return Optional.empty();
        }
        return Optional.of(new PriceQuery(
            instance.region(), instance.instanceType(), os.operatingSystem(), tenancy, os.licenseModel(),
            os.preInstalledSw()));
    }
}
