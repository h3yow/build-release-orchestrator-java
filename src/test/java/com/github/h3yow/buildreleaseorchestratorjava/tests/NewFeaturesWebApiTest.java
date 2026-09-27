package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新能力经真实 HTTP 入口验证：
 * 构建输入驱动的缓存判定、未知淘汰策略拒绝、期望版本冲突 409 且发布指针不移动。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NewFeaturesWebApiTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode trigger(String pipeline, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/pipelines/{p}/trigger", pipeline)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void buildInputCacheReasonsAndVersionConflictAreObservableViaHttp() throws Exception {
        String pipeline = "p-web-new";

        // 1. 首次触发携带构建输入：无条目
        JsonNode first = trigger(pipeline,
                "{\"idempotencyKey\":\"w1\",\"sourceRef\":\"ref-a\",\"sourceContent\":\"src-v1\"}");
        String v1 = first.get("run").get("producedVersion").asText();
        System.out.printf("[test] 首次触发: cacheReason=%s, version=%s%n",
                first.get("run").get("cacheReason").asText(), v1);
        assertEquals("MISS_NO_ENTRY", first.get("run").get("cacheReason").asText());

        // 2. 相同输入再次触发：命中复用，制品校验和一致
        JsonNode second = trigger(pipeline,
                "{\"idempotencyKey\":\"w2\",\"sourceRef\":\"ref-a\",\"sourceContent\":\"src-v1\"}");
        System.out.printf("[test] 相同输入: cacheReason=%s, reused=%s%n",
                second.get("run").get("cacheReason").asText(),
                second.get("run").get("reusedBuild").asBoolean());
        assertEquals("HIT", second.get("run").get("cacheReason").asText());
        assertTrue(second.get("run").get("reusedBuild").asBoolean());

        MvcResult a1 = mockMvc.perform(get("/api/artifacts").param("pipelineId", pipeline))
                .andExpect(status().isOk()).andReturn();
        JsonNode artifacts = om.readTree(a1.getResponse().getContentAsString());
        String sum1 = artifacts.get(0).get("checksum").asText();
        String sum2 = artifacts.get(1).get("checksum").asText();
        assertEquals(sum1, sum2, "相同输入的两次产出校验和必须一致");

        // 3. 输入变化：不兼容
        JsonNode changed = trigger(pipeline,
                "{\"idempotencyKey\":\"w3\",\"sourceRef\":\"ref-a\",\"sourceContent\":\"src-v2\"}");
        System.out.printf("[test] 内容变化: cacheReason=%s%n", changed.get("run").get("cacheReason").asText());
        assertEquals("MISS_INCOMPATIBLE", changed.get("run").get("cacheReason").asText());
        assertFalse(changed.get("run").get("reusedBuild").asBoolean());

        // 4. 未知淘汰策略必须 400 明确失败
        MvcResult badPolicy = mockMvc.perform(post("/api/cache/policy")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"policy\":\"RANDOM\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode policyErr = om.readTree(badPolicy.getResponse().getContentAsString());
        System.out.printf("[test] 未知策略: code=%s%n", policyErr.get("code").asText());
        assertEquals("UNKNOWN_EVICTION_POLICY", policyErr.get("code").asText());

        // 合法切换 FIFO 成功
        mockMvc.perform(post("/api/cache/policy")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"policy\":\"FIFO\"}"))
                .andExpect(status().isOk());
        // 还原 LRU
        mockMvc.perform(post("/api/cache/policy")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"policy\":\"LRU\"}"))
                .andExpect(status().isOk());

        // 5a. 指定已被占用的版本且内容一致：409 VERSION_ALREADY_EXISTS
        MvcResult conflictSame = mockMvc.perform(post("/api/pipelines/{p}/trigger", pipeline)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"w4\",\"sourceRef\":\"ref-a\",\"sourceContent\":\"src-v1\","
                                + "\"expectedVersion\":\"" + v1 + "\"}"))
                .andExpect(status().isConflict()).andReturn();
        JsonNode sameErr = om.readTree(conflictSame.getResponse().getContentAsString());
        System.out.printf("[test] 版本冲突(一致): code=%s%n", sameErr.get("code").asText());
        assertEquals("VERSION_ALREADY_EXISTS", sameErr.get("code").asText());

        // 5b. 同名版本但内容不一致：409 CHECKSUM_MISMATCH
        MvcResult conflictDiff = mockMvc.perform(post("/api/pipelines/{p}/trigger", pipeline)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"w5\",\"sourceRef\":\"ref-a\",\"sourceContent\":\"src-v2\","
                                + "\"expectedVersion\":\"" + v1 + "\"}"))
                .andExpect(status().isConflict()).andReturn();
        JsonNode diffErr = om.readTree(conflictDiff.getResponse().getContentAsString());
        System.out.printf("[test] 版本冲突(不一致): code=%s%n", diffErr.get("code").asText());
        assertEquals("CHECKSUM_MISMATCH", diffErr.get("code").asText());

        // 发布指针仍为冲突前的最新版本，未被移动
        MvcResult rel = mockMvc.perform(get("/api/pipelines/{p}/release", pipeline))
                .andExpect(status().isOk()).andReturn();
        String current = om.readTree(rel.getResponse().getContentAsString()).get("currentVersion").asText();
        System.out.printf("[test] 冲突后发布指针=%s (期望=%s)%n", current, changed.get("run").get("producedVersion").asText());
        assertEquals(changed.get("run").get("producedVersion").asText(), current);
    }
}
