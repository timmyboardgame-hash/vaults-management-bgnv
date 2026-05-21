package com.vault.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;

import java.net.URI;

/**
 * AWS client beans — สร้างเฉพาะเมื่อ aws.iot.endpoint ไม่ใช่ค่าว่าง
 *
 * @ConditionalOnProperty จะ match ถ้า property มีอยู่แม้ value เป็น "" (empty string)
 * ใช้ @ConditionalOnExpression เพื่อเช็ค length > 0 แทน
 */
@Configuration
public class AwsConfig {

    private static final String IOT_CONFIGURED = "'${aws.iot.endpoint:}'.length() > 0";

    @Value("${aws.region}")
    private String region;

    @Value("${aws.iot.endpoint:}")
    private String iotEndpoint;

    /** IoT management plane — CreateJob, DescribeJobExecution */
    @Bean
    @ConditionalOnExpression(IOT_CONFIGURED)
    public IotClient iotClient() {
        return IotClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /** IoT data plane — GetThingShadow, UpdateThingShadow, Publish */
    @Bean
    @ConditionalOnExpression(IOT_CONFIGURED)
    public IotDataPlaneClient iotDataPlaneClient() {
        return IotDataPlaneClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .endpointOverride(URI.create("https://" + iotEndpoint))
                .build();
    }

}
