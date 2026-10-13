package com.cloudcost.fakes;

import com.cloudcost.domain.ec2.Ec2Instance;
import java.time.Instant;

public final class Fixtures {

    public static final String CUSTOMER_ROLE_ARN = "arn:aws:iam::123456789012:role/CloudCostReadOnlyRole";

    private Fixtures() {}

    public static Ec2Instance instance(String id, String type, String state, Instant launchTime) {
        return new Ec2Instance(id, "name-" + id, type, "ap-northeast-2", "ap-northeast-2a", state, launchTime,
                "Linux/UNIX", "default", false, false);
    }

    public static Ec2Instance spot(String id, String type, String state, Instant launchTime) {
        return new Ec2Instance(id, "spot-" + id, type, "ap-northeast-2", "ap-northeast-2a", state, launchTime,
                "Linux/UNIX", "default", true, false);
    }
}
