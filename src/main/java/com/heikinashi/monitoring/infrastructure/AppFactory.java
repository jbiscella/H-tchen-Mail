package com.heikinashi.monitoring.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.application.IngestionConfig;
import com.heikinashi.monitoring.application.InstrumentRegistry;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.UuidGenerator;
import com.heikinashi.monitoring.infrastructure.bedrock.BedrockConfig;
import com.heikinashi.monitoring.infrastructure.email.SesConfig;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Factory
public class AppFactory {

    @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }

    // ----- AWS SDK clients ---------------------------------------------------
    // Compute / data-plane services run in aws.region (eu-central-1 default).
    // SES lives in its own region per CLAUDE.md §1 ADR.

    @Singleton
    public DynamoDbClient dynamoDbClient(AwsRegionConfig aws) {
        return DynamoDbClient.builder().region(Region.of(aws.getRegion())).build();
    }

    @Singleton
    public BedrockRuntimeClient bedrockRuntimeClient(BedrockConfig bedrock) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(bedrock.getRegion()))
                .build();
    }

    @Singleton
    public SesV2Client sesV2Client(SesConfig ses) {
        return SesV2Client.builder().region(Region.of(ses.getRegion())).build();
    }

    @Singleton
    public InstrumentRegistry instrumentRegistry(
            InstrumentRepository repository,
            Clock clock,
            UuidGenerator uuids,
            @Value("${monitoring.exchanges.supported}") String supportedCsv) {
        Set<String> supported = Arrays.stream(supportedCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new InstrumentRegistry(repository, clock, uuids, supported);
    }

    @Singleton
    public IngestionConfig ingestionConfig(
            @Value("${monitoring.ingest.circuit-breaker.threshold:3}") int circuitBreakerThreshold,
            @Value("${monitoring.ingest.failure-rate-alert:0.5}") double failureRateAlert,
            @Value("${monitoring.bootstrap.size-1d:250}") int bootstrap1d,
            @Value("${monitoring.bootstrap.size-1w:260}") int bootstrap1w,
            @Value("${monitoring.exchanges.suffix-map:{}}") String suffixMapJson) {
        Map<Timeframe, Integer> bootstrap = new HashMap<>();
        bootstrap.put(Timeframe.D1, bootstrap1d);
        bootstrap.put(Timeframe.W1, bootstrap1w);

        Map<String, String> suffixMap;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = new ObjectMapper().readValue(suffixMapJson, Map.class);
            suffixMap = parsed;
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse exchange suffix map JSON: " + suffixMapJson, e);
        }
        return new IngestionConfig(circuitBreakerThreshold, failureRateAlert, bootstrap, suffixMap);
    }
}
