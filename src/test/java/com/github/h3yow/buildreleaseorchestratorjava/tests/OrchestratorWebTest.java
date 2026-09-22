package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class OrchestratorWebTest {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorWebTest.class);

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void httpTriggerIsIdempotentAndSurfacesDistinguishableReplay() throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "idempotencyKey", "idem-web-1",
                "stageDelayMillis", 1));

        log.info("INPUT HTTP trigger body={}", body);
        String firstResponse = mockMvc.perform(post("/api/orchestrator/pipelines/default/trigger")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("STARTED"))
                .andExpect(jsonPath("$.runStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.version").value("1.0.1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode firstJson = mapper.readTree(firstResponse);
        String runId = firstJson.get("runId").asText();
        log.info("DECISION first HTTP runId={} version={}", runId, firstJson.get("version").asText());

        mockMvc.perform(post("/api/orchestrator/pipelines/default/trigger")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE_REPLAY"))
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.version").value("1.0.1"));
        log.info("DECISION duplicate HTTP submission replayed runId={}", runId);

        mockMvc.perform(get("/api/orchestrator/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resolvedVersion").value("1.0.1"));

        mockMvc.perform(get("/api/orchestrator/pipelines/default/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("1.0.1"));
    }

    @Test
    void httpUnknownRunReturns404AndRunLimitConflictReturns409() throws Exception {
        mockMvc.perform(get("/api/orchestrator/runs/run-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        log.info("DECISION unknown run mapped to 404 RUN_NOT_FOUND");

        String body = mapper.writeValueAsString(java.util.Map.of(
                "idempotencyKey", "idem-web-bad-version",
                "requestedVersion", "9.9.9",
                "stageDelayMillis", 1));
        mockMvc.perform(post("/api/orchestrator/pipelines/default/trigger")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        log.info("DECISION illegal version over HTTP mapped to 409 VERSION_CONFLICT");
    }
}
