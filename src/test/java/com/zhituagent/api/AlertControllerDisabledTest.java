package com.zhituagent.api;

import com.zhituagent.ZhituAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that with multi-agent disabled (the default), the
 * {@code AlertController} bean is not registered — preserving the v2 surface
 * as M1 promises. With the bean absent, {@code POST /api/alert} returns
 * 404 in production.
 */
@SpringBootTest(classes = ZhituAgentApplication.class)
class AlertControllerDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldNotRegisterAlertControllerWhenMultiAgentDisabled() {
        assertThat(context.getBeansOfType(AlertController.class)).isEmpty();
    }
}
