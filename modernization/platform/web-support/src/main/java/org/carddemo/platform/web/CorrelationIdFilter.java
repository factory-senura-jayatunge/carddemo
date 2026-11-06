package org.carddemo.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes {@code X-Correlation-Id} from the request (or makes one), puts it in the log MDC as
 * {@code correlationId} and echoes it on the response, so a client report can be matched to the log.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !SAFE.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
