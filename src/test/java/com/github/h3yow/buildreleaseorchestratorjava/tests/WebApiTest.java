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
 * Web 入口一致性：REST 触发/重试/回滚与服务层语义一致，重复请求返回可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void triggerRetryRollbackViaRestAreConsistent() throws Exception {
        String pipelineId = "p-web";
        System.out.printf("[test] 输入: POST /api/pipelines/%s/trigger, idempotencyKey=web-k1, failAtStage=TEST%n", pipelineId);

        // 1. 触发（注入失败）
        MvcResult r1 = mockMvc.perform(post("/api/pipelines/{p}/trigger", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"web-k1\",\"failAtStage\":\"TEST\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode first = om.readTree(r1.getResponse().getContentAsString());
        String runId = first.get("run").get("runId").asText();

        // 2. 相同幂等键重复触发：返回同一运行且 duplicated=true
        MvcResult r2 = mockMvc.perform(post("/api/pipelines/{p}/trigger", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"web-k1\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode dup = om.readTree(r2.getResponse().getContentAsString());
        System.out.printf("[test] 判定依据: first.duplicated=%s, dup.duplicated=%s, 同一runId=%s%n",
                first.get("duplicated").asBoolean(), dup.get("duplicated").asBoolean(),
                runId.equals(dup.get("run").get("runId").asText()));
        assertFalse(first.get("duplicated").asBoolean());
        assertTrue(dup.get("duplicated").asBoolean(), "重复请求必须与首次可区分");
        assertEquals(runId, dup.get("run").get("runId").asText());

        // 3. 重试：从失败点继续并成功
        MvcResult r3 = mockMvc.perform(post("/api/runs/{id}/retry", runId))
                .andExpect(status().isOk()).andReturn();
        JsonNode retried = om.readTree(r3.getResponse().getContentAsString());
        assertEquals("SUCCESS", retried.get("status").asText());
        String version = retried.get("producedVersion").asText();

        // 4. 查询运行状态与阶段耗时
        MvcResult r4 = mockMvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk()).andReturn();
        JsonNode run = om.readTree(r4.getResponse().getContentAsString());
        System.out.printf("[test] 判定依据: version=%s, cacheReason=%s, 阶段数=%d%n",
                version, run.get("cacheReason").asText(), run.get("stages").size());
        assertEquals(5, run.get("stages").size());
        assertTrue(run.get("stages").get(0).has("durationMillis"), "阶段耗时必须可查询");

        // 5. 再发布一个版本后回滚
        mockMvc.perform(post("/api/pipelines/{p}/trigger", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"web-k2\"}"))
                .andExpect(status().isOk());
        MvcResult r5 = mockMvc.perform(post("/api/pipelines/{p}/rollback", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":\"" + version + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rollback = om.readTree(r5.getResponse().getContentAsString());
        assertEquals(version, rollback.get("currentVersion").asText());
        assertTrue(rollback.get("changed").asBoolean());

        // 6. 回滚到不存在版本：409 + 原因码
        MvcResult r6 = mockMvc.perform(post("/api/pipelines/{p}/rollback", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":\"9.9.9\"}"))
                .andExpect(status().isConflict()).andReturn();
        JsonNode err = om.readTree(r6.getResponse().getContentAsString());
        System.out.printf("[test] 判定依据: 回滚不存在版本 -> code=%s%n", err.get("code").asText());
        assertEquals("ARTIFACT_NOT_FOUND", err.get("code").asText());
    }

    @Test
    void requestedVersionConflictViaRestIsRejectedWithDistinctCodes() throws Exception {
        String pipelineId = "p-web-version";
        System.out.printf("[test] 输入: POST /api/pipelines/%s/trigger 两次指定 requestedVersion=2.0.0，第二次内容不同%n",
                pipelineId);

        // 1. 指定版本首次触发成功
        MvcResult ok = mockMvc.perform(post("/api/pipelines/{p}/trigger", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"wv-1\",\"sourceRef\":\"git://repo#main\","
                                + "\"sourceContent\":\"content-A\",\"requestedVersion\":\"2.0.0\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("2.0.0", om.readTree(ok.getResponse().getContentAsString())
                .get("run").get("producedVersion").asText());

        // 2. 相同输入指定同一版本：409 VERSION_ALREADY_EXISTS
        MvcResult same = mockMvc.perform(post("/api/pipelines/{p}/trigger", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"wv-2\",\"sourceRef\":\"git://repo#main\","
                                + "\"sourceContent\":\"content-A\",\"requestedVersion\":\"2.0.0\"}"))
                .andExpect(status().isConflict()).andReturn();
        JsonNode sameErr = om.readTree(same.getResponse().getContentAsString());
        System.out.printf("[test] 判定依据: 同内容冲突 -> code=%s%n", sameErr.get("code").asText());
        assertEquals("VERSION_ALREADY_EXISTS", sameErr.get("code").asText());

        // 3. 不同内容指定同一版本：409 CHECKSUM_MISMATCH
        MvcResult diff = mockMvc.perform(post("/api/pipelines/{p}/trigger", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"wv-3\",\"sourceRef\":\"git://repo#main\","
                                + "\"sourceContent\":\"content-B\",\"requestedVersion\":\"2.0.0\"}"))
                .andExpect(status().isConflict()).andReturn();
        JsonNode diffErr = om.readTree(diff.getResponse().getContentAsString());
        System.out.printf("[test] 判定依据: 不同内容冲突 -> code=%s%n", diffErr.get("code").asText());
        assertEquals("CHECKSUM_MISMATCH", diffErr.get("code").asText());

        // 4. 发布指向保持 2.0.0，未被拒绝的请求移动
        MvcResult rel = mockMvc.perform(get("/api/pipelines/{p}/release", pipelineId))
                .andExpect(status().isOk()).andReturn();
        JsonNode release = om.readTree(rel.getResponse().getContentAsString());
        System.out.printf("[test] 判定依据: currentVersion=%s%n", release.get("currentVersion").asText());
        assertEquals("2.0.0", release.get("currentVersion").asText(), "冲突拒绝后发布指向不得移动");
    }
}
