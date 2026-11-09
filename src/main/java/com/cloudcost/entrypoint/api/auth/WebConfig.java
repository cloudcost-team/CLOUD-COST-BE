package com.cloudcost.entrypoint.api.auth;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

    private final OwnerArgumentResolver ownerResolver;
    private final String[] allowedOrigins;

    WebConfig(OwnerArgumentResolver ownerResolver, @Value("${cci.cors.allowed-origins}") String[] allowedOrigins) {
        this.ownerResolver = ownerResolver;
        this.allowedOrigins = allowedOrigins;
    }

    /** 프론트 출처 허용. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(ownerResolver);
    }
}
