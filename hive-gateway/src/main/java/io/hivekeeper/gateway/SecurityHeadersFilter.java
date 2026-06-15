package io.hivekeeper.gateway;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import java.io.IOException;

/**
 * Baseline security response headers on every gateway response — defense in depth for the JSON API and the
 * agent uplink. Profile-independent (a plain servlet filter, so it applies whether or not the OIDC security
 * chains are active). Browsers never frame the API and it is not HTML, but nosniff / DENY / no-referrer are
 * cheap and prevent a few footguns (MIME sniffing, clickjacking if ever embedded, referrer leakage).
 */
@Component
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("X-Frame-Options", "DENY");
            http.setHeader("Referrer-Policy", "no-referrer");
        }
        chain.doFilter(request, response);
    }
}
