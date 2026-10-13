package com.cloudcost.entrypoint.api.auth;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

    private final OwnerArgumentResolver ownerResolver;

    WebConfig(OwnerArgumentResolver ownerResolver) {
        this.ownerResolver = ownerResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(ownerResolver);
    }
}
