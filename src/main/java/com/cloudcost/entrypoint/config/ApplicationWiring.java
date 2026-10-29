package com.cloudcost.entrypoint.config;

import com.cloudcost.application.AuthService;
import com.cloudcost.application.AwsAccountService;
import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.Ec2DataRefresher;
import com.cloudcost.application.Ec2UsageQueryService;
import com.cloudcost.application.Pauser;
import com.cloudcost.application.PriceService;
import com.cloudcost.application.StackCallbackService;
import com.cloudcost.application.UsageSettings;
import com.cloudcost.application.port.AccessTokens;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.application.port.CustomerAwsGateway;
import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.application.port.ExchangeRateRepository;
import com.cloudcost.application.port.ExchangeRateSource;
import com.cloudcost.application.port.GoogleIdentityVerifier;
import com.cloudcost.application.port.PriceCacheRepository;
import com.cloudcost.application.port.PriceCatalog;
import com.cloudcost.application.port.StackCallbackResponder;
import com.cloudcost.application.port.SyncJobRepository;
import com.cloudcost.application.port.UnitOfWork;
import com.cloudcost.application.port.UserRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 조립(wiring)은 entrypoint에서 한다. application 계층은 Spring을 모른다. */
@Configuration
class ApplicationWiring {

    ApplicationWiring(CloudCostProperties properties) {
        CloudCostProperties.Auth auth = properties.auth();
        if (!auth.devMode() && !"google".equalsIgnoreCase(auth.mode())) {
            throw new IllegalStateException("AUTH_MODE는 dev 또는 google이어야 합니다.");
        }
        if (auth.devMode() && !"local".equalsIgnoreCase(properties.appEnv())) {
            throw new IllegalStateException("AUTH_MODE=dev는 APP_ENV=local에서만 쓸 수 있습니다.");
        }
        if (!auth.devMode() && (isBlank(auth.googleClientId()) || isBlank(auth.jwtSecret()))) {
            throw new IllegalStateException("AUTH_MODE=google에는 GOOGLE_CLIENT_ID와 JWT_SECRET이 필요합니다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Bean
    AuthService authService(
            GoogleIdentityVerifier google,
            AccessTokens tokens,
            UserRepository users,
            UnitOfWork unitOfWork,
            Clock clock) {
        return new AuthService(google, tokens, users, unitOfWork, clock);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UsageSettings usageSettings(CloudCostProperties properties) {
        return properties.usageSettings();
    }

    @Bean
    AwsAccountService awsAccountService(
            AwsAccountRepository accounts,
            UserRepository users,
            Ec2InstanceRepository instances,
            Ec2UsageRepository usage,
            SyncJobRepository syncJobs,
            CustomerAwsGateway gateway,
            UnitOfWork unitOfWork,
            Clock clock,
            CloudCostProperties properties) {
        return new AwsAccountService(
                accounts, users, instances, usage, syncJobs, gateway, unitOfWork, clock,
                properties.connectionSettings());
    }

    @Bean
    Ec2DataRefresher ec2DataRefresher(
            AwsAccountRepository accounts,
            Ec2InstanceRepository instances,
            Ec2UsageRepository usage,
            SyncJobRepository syncJobs,
            CustomerAwsGateway gateway,
            UnitOfWork unitOfWork,
            Clock clock,
            UsageSettings settings,
            Pauser pauser) {
        return new Ec2DataRefresher(
                accounts, instances, usage, syncJobs, gateway, unitOfWork, clock, settings, pauser);
    }

    @Bean
    Pauser pauser() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Bean
    StackCallbackService stackCallbackService(
            AwsAccountRepository accounts,
            AwsAccountService accountService,
            StackCallbackResponder responder,
            Clock clock,
            CloudCostProperties properties,
            Pauser pauser) {
        return new StackCallbackService(
                accounts, accountService, responder, clock, properties.connectionSettings(), pauser);
    }

    @Bean
    CurrencyService currencyService(
            ExchangeRateSource source,
            ExchangeRateRepository cache,
            Clock clock,
            @Value("${cci.fx.refresh-interval:6h}") Duration refreshInterval) {
        return new CurrencyService(source, cache, clock, refreshInterval);
    }

    @Bean
    PriceService priceService(PriceCatalog catalog, PriceCacheRepository cache, Clock clock, UsageSettings settings) {
        return new PriceService(catalog, cache, clock, settings);
    }

    @Bean
    Ec2UsageQueryService ec2UsageQueryService(
            AwsAccountService accounts,
            Ec2DataRefresher refresher,
            Ec2InstanceRepository instances,
            Ec2UsageRepository usage,
            PriceService prices,
            Clock clock,
            UsageSettings settings) {
        return new Ec2UsageQueryService(accounts, refresher, instances, usage, prices, clock, settings);
    }
}
