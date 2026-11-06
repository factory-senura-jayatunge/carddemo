package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.IdentityFixtures.AuditRow;
import org.carddemo.identity.IdentityFixtures.SessionRow;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;

/** SGN S15 to S17, sign-off (FLOW-SGN-003) and refresh rotation. */
class SessionLifecycleTest extends IdentityTestBase {

    private static void assertExpired(Response r) {
        assertThat(r.status()).as("%s", r.body()).isEqualTo(401);
        assertThat(r.text("code")).isEqualTo("SESSION_EXPIRED");
        assertThat(r.text("message")).isEqualTo("Your session has ended. Please sign on again ...");
        assertThat(r.cookie("cd_at").attr("Max-Age")).isEqualTo("0");
        assertThat(r.cookie("cd_rt").attr("Max-Age")).isEqualTo("0");
    }

    @Test
    void refreshRotatesTheTokensAndKeepsTheSession() {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        String access = c.cookieValue("cd_at");
        String refresh = c.cookieValue("cd_rt");
        String xsrf = c.cookieValue("XSRF-TOKEN");
        clock.advance(Duration.ofMinutes(4));

        Response r = c.refresh();
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.text("userId")).isEqualTo("USER0001");
        assertThat(r.text("role")).isEqualTo("USER");
        assertThat(c.cookieValue("cd_at")).isNotEqualTo(access);
        assertThat(c.cookieValue("cd_rt")).isNotEqualTo(refresh);
        assertThat(c.cookieValue("XSRF-TOKEN")).isNotEqualTo(xsrf);
        assertThat(r.cookie("cd_rt").attr("Max-Age")).isEqualTo(Long.toString(8 * 3600 - 4 * 60));
        assertThat(db.sessions()).hasSize(1);
        assertThat(c.me().status()).isEqualTo(200);
    }

    @Test
    @SpecItem({"Q-SGN-003"})
    void s15_refreshAfterSixteenIdleMinutes() {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        clock.advance(Duration.ofMinutes(15));
        assertThat(c.refresh().status()).as("15 minutes is still within the limit").isEqualTo(200);
        clock.advance(Duration.ofMinutes(16));
        assertExpired(c.refresh());
        assertThat(db.sessions()).extracting(SessionRow::endReason).containsExactly("IDLE");
        assertThat(db.audit()).last().isEqualTo(new AuditRow("SESSION_EXPIRED", null, "USER0001", "IDLE", "127.0.0.1"));
    }

    @Test
    @SpecItem({"Q-SGN-003"})
    void refreshIsRefusedAfterEightHoursWhateverTheActivity() {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        int refreshed = 0;
        Response r;
        while (true) {
            clock.advance(Duration.ofMinutes(14));
            r = c.refresh();
            if (r.status() != 200) {
                break;
            }
            refreshed++;
        }
        assertExpired(r);
        assertThat(refreshed).isEqualTo(480 / 14);
        assertThat(db.sessions()).extracting(SessionRow::endReason).containsExactly("EXPIRED");
    }

    @Test
    @SpecItem({"FLOW-SGN-003"})
    void s16_signOffThenRefreshWithTheOldCookie() {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        String refresh = c.cookieValue("cd_rt");
        String access = c.cookieValue("cd_at");

        Response off = c.signOff();
        assertThat(off.status()).isEqualTo(200);
        assertThat(off.text("message")).isEqualTo("Thank you for using CardDemo application...");
        assertThat(off.text("severity")).isEqualTo("info");
        assertThat(off.cookie("cd_at").attr("Max-Age")).isEqualTo("0");
        assertThat(off.cookie("cd_rt").attr("Max-Age")).isEqualTo("0");
        assertThat(db.sessions()).extracting(SessionRow::endReason).containsExactly("SIGN_OFF");
        assertThat(db.audit()).last().isEqualTo(new AuditRow("SIGN_OFF", "USER0001", "USER0001", null, "127.0.0.1"));

        c.setCookie("cd_rt", refresh, "/api/auth");
        assertExpired(c.refresh());
        // The access token is still within its 5 minutes, but the session it names has ended.
        c.setCookie("cd_at", access, "/api");
        assertThat(c.me().status()).isEqualTo(401);
        assertThat(c.signOff().status()).as("sign-off always succeeds").isEqualTo(200);
    }

    @Test
    void signOffWithOnlyTheRefreshCookieEndsTheSession() {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        c.dropCookie("cd_at");
        assertThat(c.signOff().status()).isEqualTo(200);
        assertThat(db.sessions()).extracting(SessionRow::endReason).containsExactly("SIGN_OFF");
    }

    @Test
    @SpecItem({"Q-SGN-003"})
    void s17_reusedRefreshTokenEndsTheSession() {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        String first = c.cookieValue("cd_rt");
        assertThat(c.refresh().status()).isEqualTo(200);
        String second = c.cookieValue("cd_rt");

        c.setCookie("cd_rt", first, "/api/auth");
        assertExpired(c.refresh());
        assertThat(db.sessions()).extracting(SessionRow::endReason).containsExactly("REUSE_DETECTED");
        assertThat(db.audit()).last()
                .isEqualTo(new AuditRow("REFRESH_REUSE", null, "USER0001", "REUSE_DETECTED", "127.0.0.1"));

        c.setCookie("cd_rt", second, "/api/auth");
        assertExpired(c.refresh());
        assertThat(db.audit()).filteredOn(a -> a.eventType().equals("REFRESH_REUSE")).hasSize(1);
    }

    @Test
    void refreshWithoutOrWithAnUnknownCookie() {
        ApiClient c = client();
        c.setCookie("XSRF-TOKEN", "t0k3n", "/");
        assertExpired(c.refresh());
        c.setCookie("cd_rt", "bm90LWEtdG9rZW4", "/api/auth");
        assertExpired(c.refresh());
        assertThat(db.audit()).isEmpty();
    }

    @Test
    void refreshAndSignOffNeedTheCsrfHeader() {
        db.demoUsers();
        ApiClient c = client().withoutCsrfHeader();
        c.signOn("USER0001", "PASSWORD");
        Response refresh = c.refresh();
        assertThat(refresh.status()).isEqualTo(403);
        assertThat(refresh.text("code")).isEqualTo("CSRF_REJECTED");
        assertThat(refresh.text("message"))
                .isEqualTo("Your request could not be verified. Reload the page and try again ...");
        assertThat(c.signOff().status()).isEqualTo(403);
        assertThat(db.sessions()).extracting(SessionRow::endReason).containsOnlyNulls();
    }
}
