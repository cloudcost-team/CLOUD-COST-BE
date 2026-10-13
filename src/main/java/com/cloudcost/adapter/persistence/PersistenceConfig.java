package com.cloudcost.adapter.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackageClasses = PersistenceConfig.class, considerNestedRepositories = true)
class PersistenceConfig {
}
