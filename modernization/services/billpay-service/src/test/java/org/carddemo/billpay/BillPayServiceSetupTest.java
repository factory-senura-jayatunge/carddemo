package org.carddemo.billpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BillPayServiceSetupTest extends BillPayTestBase {

    @Autowired
    FidelitySwitches switches;

    /** make up and make status poll it before any user has signed on. */
    @Test
    void healthNeedsNoSession() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }

    @Test
    void theSwitchesComeFromTheLedgerNotFromTheServiceConfiguration() throws Exception {
        assertThat(switches.effectiveModes()).containsKeys("DEF-BILL-010", "BR-TRNA-003");
        for (String file : new String[] {"application.yml", "application-demo.yml"}) {
            assertThat(Files.readString(Path.of("src/main/resources", file))).doesNotContain("fidelity");
        }
    }
}
