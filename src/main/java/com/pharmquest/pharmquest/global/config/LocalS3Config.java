package com.pharmquest.pharmquest.global.config;

import com.amazonaws.services.s3.AbstractAmazonS3;
import com.amazonaws.services.s3.AmazonS3;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Allows local DB development without AWS; upload operations remain unsupported. */
@Configuration
@Profile("local")
public class LocalS3Config {
    @Bean
    public AmazonS3 amazonS3() {
        return new AbstractAmazonS3() {
            @Override
            public void shutdown() {
                // No network client or resources are created in the local profile.
            }
        };
    }
}
