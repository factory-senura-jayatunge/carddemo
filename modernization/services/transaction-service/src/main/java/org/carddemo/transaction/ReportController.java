package org.carddemo.transaction;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.carddemo.transaction.api.ReportApi;
import org.carddemo.transaction.api.ReportRequest;
import org.carddemo.transaction.api.ReportRun;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit RPTS (CORPT00C): main-menu option 9 is a USER option; a run is visible to its requester and
 * to any ADMIN (approved access rule), who reaches it by its ID only.
 */
@RestController
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class ReportController implements ReportApi {

    static final MediaType REPORT_TEXT = MediaType.parseMediaType("text/plain; charset=US-ASCII");

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @Override
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ReportRun> requestTransactionReport(ReportRequest request, Authentication authentication) {
        ReportRun run = service.request(request, userId(authentication));
        return ResponseEntity.accepted().location(URI.create("/api/reports/transactions/" + run.runId())).body(run);
    }

    @Override
    public ReportRun getTransactionReport(String runId, Authentication authentication) {
        return service.status(runId, userId(authentication), admin(authentication));
    }

    /**
     * Written to the response on the request thread, never as an async body: an async writer that
     * never runs would leave the snapshot's channel, and its disk space, open.
     */
    @Override
    public void downloadTransactionReport(String runId, Authentication authentication, HttpServletResponse response) {
        service.download(runId, userId(authentication), admin(authentication), response);
    }

    private static String userId(Authentication authentication) {
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : authentication.getName();
    }

    private static boolean admin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
