package com.cloudcost.entrypoint.config;

import com.cloudcost.application.AlertEvaluationService;
import com.cloudcost.application.AuthService;
import com.cloudcost.application.AwsAccountService;
import com.cloudcost.application.BenchmarkService;
import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.Ec2DataRefresher;
import com.cloudcost.application.DashboardService;
import com.cloudcost.application.DemoUsageService;
import com.cloudcost.application.Ec2UsageQueryService;
import com.cloudcost.application.NotificationChannelService;
import com.cloudcost.application.NotificationDispatchService;
import com.cloudcost.application.NotificationService;
import com.cloudcost.application.Pauser;
import com.cloudcost.application.PriceService;
import com.cloudcost.application.ReportService;
import com.cloudcost.application.ResourceScanService;
import com.cloudcost.application.ResourceWasteService;
import com.cloudcost.application.ScheduledCollectionService;
import com.cloudcost.application.SimulationService;
import com.cloudcost.application.StackCallbackService;
import com.cloudcost.application.UsageSettings;
import com.cloudcost.application.WasteService;
import com.cloudcost.application.port.AccessTokens;
import com.cloudcost.application.port.AccountResourceRepository;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.application.port.BenchmarkReferenceSource;
import com.cloudcost.application.port.AlertSettingsRepository;
import com.cloudcost.application.port.BudgetRepository;
import com.cloudcost.application.port.NotificationChannelGateway;
import com.cloudcost.application.port.NotificationChannelRepository;
import com.cloudcost.application.port.NotificationDeliveryRepository;
import com.cloudcost.application.port.NotificationRepository;
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
import com.cloudcost.application.port.RefreshTokenRepository;
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
            RefreshTokenRepository refreshTokens,
            CloudCostProperties properties,
            UnitOfWork unitOfWork,
            Clock clock) {
        return new AuthService(
                google, tokens, users, refreshTokens, properties.auth().refreshTokenTtl(), unitOfWork, clock);
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
            BudgetRepository budgets,
            NotificationRepository notifications,
            AccountResourceRepository resources,
            CustomerAwsGateway gateway,
            UnitOfWork unitOfWork,
            Clock clock,
            CloudCostProperties properties) {
        return new AwsAccountService(
                accounts, users, instances, usage, syncJobs, budgets, notifications, resources, gateway, unitOfWork,
                clock, properties.connectionSettings());
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

    @Bean
    WasteService wasteService(
            AwsAccountService accounts,
            Ec2DataRefresher refresher,
            Ec2InstanceRepository instances,
            Ec2UsageRepository usage,
            PriceService prices,
            Clock clock,
            UsageSettings settings) {
        return new WasteService(accounts, refresher, instances, usage, prices, clock, settings);
    }

    @Bean
    DashboardService dashboardService(
            AwsAccountService accounts,
            Ec2UsageQueryService queries,
            DemoUsageService demo,
            BudgetRepository budgets,
            CurrencyService currencies,
            Clock clock,
            UsageSettings settings) {
        return new DashboardService(accounts, queries, demo, budgets, currencies, clock, settings);
    }

    @Bean
    SimulationService simulationService(
            WasteService waste, DashboardService dashboards, DemoUsageService demo, Clock clock) {
        return new SimulationService(waste, dashboards, demo, clock);
    }

    @Bean
    BenchmarkService benchmarkService(
            WasteService waste,
            DashboardService dashboards,
            DemoUsageService demo,
            BenchmarkReferenceSource references,
            Clock clock) {
        return new BenchmarkService(waste, dashboards, demo, references, clock);
    }

    @Bean
    AlertEvaluationService alertEvaluationService(
            AwsAccountService accounts,
            DashboardService dashboards,
            Ec2UsageQueryService queries,
            AlertSettingsRepository settings,
            NotificationRepository notifications,
            CurrencyService currencies,
            Clock clock,
            UsageSettings usage) {
        return new AlertEvaluationService(
                accounts, dashboards, queries, settings, notifications, currencies, clock, usage);
    }

    @Bean
    NotificationService notificationService(
            AwsAccountService accounts,
            DashboardService dashboards,
            BudgetRepository budgets,
            AlertSettingsRepository settings,
            NotificationRepository notifications,
            NotificationChannelRepository channels,
            NotificationDeliveryRepository deliveries,
            AlertEvaluationService alerts,
            UnitOfWork unitOfWork,
            Clock clock) {
        return new NotificationService(
                accounts, dashboards, budgets, settings, notifications, channels, deliveries, alerts, unitOfWork,
                clock);
    }

    @Bean
    NotificationChannelService notificationChannelService(
            NotificationChannelRepository channels, NotificationChannelGateway gateway, Clock clock) {
        return new NotificationChannelService(channels, gateway, clock);
    }

    @Bean
    NotificationDispatchService notificationDispatchService(
            NotificationDeliveryRepository deliveries,
            NotificationChannelGateway gateway,
            Clock clock,
            CloudCostProperties properties) {
        return new NotificationDispatchService(deliveries, gateway, clock, properties.notification().appUrl());
    }

    @Bean
    ScheduledCollectionService scheduledCollectionService(
            AwsAccountRepository accounts,
            Ec2DataRefresher refresher,
            ResourceScanService resourceScans,
            AlertEvaluationService alerts) {
        return new ScheduledCollectionService(accounts, refresher, resourceScans, alerts);
    }

    @Bean
    ResourceScanService resourceScanService(
            AwsAccountRepository accounts, AccountResourceRepository resources, CustomerAwsGateway gateway, Clock clock,
            @org.springframework.beans.factory.annotation.Qualifier("resourceScanExecutor")
            java.util.concurrent.Executor resourceScanExecutor) {
        return new ResourceScanService(accounts, resources, gateway, clock, resourceScanExecutor);
    }

    /** 화면 조회가 맡기는 백그라운드 확장 수집 (ADR-020). 동시에 2개 연결까지, 대기 50개를 넘으면 이번 조회는 맡기지 않는다 (다음 조회 때 다시) */
    @Bean(destroyMethod = "shutdownNow")
    java.util.concurrent.ExecutorService resourceScanExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(50),
            Thread.ofPlatform().name("resource-scan-", 0).daemon(true).factory(),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ResourceWasteService resourceWasteService(
            AwsAccountService accounts,
            ResourceScanService scans,
            AccountResourceRepository resources,
            PriceService prices,
            Clock clock,
            CloudCostProperties properties) {
        return new ResourceWasteService(accounts, scans, resources, prices, clock, properties.aws().templateUrl());
    }

    @Bean
    ReportService reportService(
            Ec2UsageQueryService queries,
            WasteService waste,
            DemoUsageService demo,
            Clock clock,
            UsageSettings settings) {
        return new ReportService(queries, waste, demo, clock, settings);
    }

    @Bean
    DemoUsageService demoUsageService(Clock clock, UsageSettings settings, CloudCostProperties properties) {
        CloudCostProperties.Demo demo = properties.demo();
        return new DemoUsageService(
                clock, settings, new DemoUsageService.DemoSettings(demo.username(), demo.password()));
    }
}
