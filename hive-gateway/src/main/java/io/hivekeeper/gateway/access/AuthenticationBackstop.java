package io.hivekeeper.gateway.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Set;

/**
 * Makes authentication the default for {@code /api/**} instead of something each controller has to remember.
 *
 * <p>Spring Security permits every path here (the resource-server filter guards only {@code /api/me}); the real
 * check is {@link AccessGuard#authenticate()}, called by hand as the first line of each handler. That worked
 * only for as long as nobody forgot: a new handler missing the call was not a compile error, not a test failure
 * and not a 401 — it was simply reachable by anyone who could reach the port. The invariant held across 45
 * handlers by discipline alone, and discipline is not a security control.
 *
 * <p>So this interceptor authenticates every {@code /api/**} request that is not on {@link #PUBLIC_PATHS}, before
 * the handler runs, and rejects it (401/400/403) if it cannot. Handlers still call {@code authenticate()} — they
 * need the {@link Principal} — but that call is memoized per request, so it costs nothing and the request is
 * already authenticated by the time it arrives. <b>Forgetting the call now fails closed.</b>
 *
 * <p>Scope: this closes "reachable by anyone", not "reachable by the wrong member". Authorization stays with the
 * handler ({@code guard.require(...)}), which is the only place that knows the resource being touched.
 */
@Component
public class AuthenticationBackstop implements HandlerInterceptor, WebMvcConfigurer {

    /**
     * The endpoints that legitimately run without an {@link AccessGuard} principal — each authenticates another
     * way, or has nothing to authenticate. Adding to this list makes an endpoint publicly reachable, so it is
     * pinned by a test: an entry here must be a deliberate, reviewed decision.
     */
    static final Set<String> PUBLIC_PATHS = Set.of(
            // Which deployment shape the SPA is talking to. Read before the browser has any identity, and it
            // discloses only whether solo mode is on.
            "/api/mode",
            // First-run setup: no operator exists yet, so there is nobody to authenticate as. Guarded instead by
            // the one-time setup token printed on the server console, plus a hard "only while uninitialized" lock.
            "/api/setup",
            "/api/setup/status",
            // The signed-in user's own profile. Authenticated by the resource server's bearer filter — the one
            // path it is installed on — and deliberately not by the guard, since it needs no X-Org.
            "/api/me",
            // Certificate renewal: the agent authenticates with its CURRENT mTLS client certificate, not as an
            // operator.
            "/api/enrollments/certificate/renew");

    /** {@code POST /api/enrollments/{token}/certificate} — the one-time token IS the credential, and it is in the
     *  path, so this one is matched by shape. */
    private static final List<String> PUBLIC_PATTERNS = List.of("^/api/enrollments/[^/]+/certificate$");

    /**
     * Optional so the {@code @WebMvcTest} slices that never exercise a guarded path (e.g. {@code /api/me}) need
     * not supply a guard. A guarded path with no guard bean is a wiring bug, and {@link #preHandle} refuses the
     * request rather than waving it through.
     */
    private final ObjectProvider<AccessGuard> guard;

    public AuthenticationBackstop(ObjectProvider<AccessGuard> guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isPublic(request.getRequestURI())) {
            return true;
        }
        AccessGuard access = guard.getIfAvailable();
        if (access == null) {
            throw new IllegalStateException("no AccessGuard: refusing to serve " + request.getRequestURI()
                    + " unauthenticated");
        }
        // Throws AccessException (401/400/403), rendered as JSON by AccessExceptionAdvice. The principal is cached
        // on the request, so the handler's own authenticate() call resolves for free.
        access.authenticate();
        return true;
    }

    static boolean isPublic(String path) {
        return PUBLIC_PATHS.contains(path) || PUBLIC_PATTERNS.stream().anyMatch(path::matches);
    }
}
