package com.github.h3yow.buildreleaseorchestratorjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrchestratorProperties.class)
public class OrchestratorConfig {
}
