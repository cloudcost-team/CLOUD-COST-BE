package com.cloudcost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CloudCostApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudCostApplication.class, args);
    }
}
