package com.github.h3yow.buildreleaseorchestratorjava.config;

import com.github.h3yow.buildreleaseorchestratorjava.engine.OrchestratorService;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageName;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DefaultPipelineInitializer {

    @Bean
    public ApplicationRunner defaultPipelines(OrchestratorService service) {
        return args -> service.registerPipeline("default", "default build/release pipeline",
                List.of(StageName.CHECKOUT.name(), StageName.BUILD.name(), StageName.TEST.name(),
                        StageName.PACKAGE.name(), StageName.RELEASE.name()));
    }
}
