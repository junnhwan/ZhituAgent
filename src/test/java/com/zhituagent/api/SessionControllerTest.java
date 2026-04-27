package com.zhituagent.api;

import com.zhituagent.ZhituAgentApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ZhituAgentApplication.class)
@AutoConfigureMockMvc
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCreateSessionShell() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user_20001",
                                  "title": "Java Agent 调试"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId", notNullValue()))
                .andExpect(jsonPath("$.userId").value("user_20001"))
                .andExpect(jsonPath("$.title").value("Java Agent 调试"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()));
    }

    @Test
    void shouldReturnSummaryAndRecentMessagesForLongConversation() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user_20001",
                                  "title": "长对话测试"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode sessionNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String sessionId = sessionNode.get("sessionId").asText();

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "sessionId": "%s",
                                      "userId": "user_20001",
                                      "message": "第%s轮问题"
                                    }
                                    """.formatted(sessionId, i)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/sessions/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("Earlier conversation summary")))
                .andExpect(jsonPath("$.recentMessages.length()").value(4));
    }
}
