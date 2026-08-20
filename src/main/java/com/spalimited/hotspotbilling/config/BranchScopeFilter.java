package com.spalimited.hotspotbilling.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Keeps a branch-scoped login inside its own branch.
 *
 * <p>A login with a branch — a franchise, a partner reselling in another town, a
 * site manager — carries a {@code BRANCH_<id>} authority added in
 * {@link BearerTokenFilter}. Everything else in the system is written as though
 * one deployment belongs to one ISP, so this is what stops that assumption
 * leaking one branch's customers to another.
 *
 * <h2>Why an allowlist and not a filter on every query</h2>
 *
 * <p>The tempting approach is to add "and branch_id = ?" to each query. There are
 * sixty-odd admin endpoints; some would be missed, and a partner who sees their
 * own customers on nine screens and everybody's on the tenth is worse than one
 * who is refused outright — because nobody finds the tenth screen until somebody
 * has read a competitor's customer list off it.
 *
 * <p>So this refuses by default. A branch session reaches only what is listed
 * below, and each entry is there because the query behind it has actually been
 * made branch-aware. Extending access is a deliberate edit to one readable list,
 * and the cost of forgetting is a locked door rather than an open one.
 *
 * <p>The mirror of {@link DemoReadOnlyFilter}, which restricts the same way for a
 * different reason.
 */
@Component
public class BranchScopeFilter extends OncePerRequestFilter {

    /**
     * What a branch login may reach. Prefix matches under {@code /api/admin}.
     *
     * <p>Every line here is a promise that the code behind it filters by branch.
     * Do not add one without making that true — see BranchScope for the helper
     * and SubscriberController for what "branch-aware" looks like in practice.
     */
    private static final List<String> ALLOWED = List.of(
            // Their own customers, and everything done to one of them. Every
            // by-id path here goes through SubscriberController.reachable(),
            // which refuses a customer belonging to another branch -- filtering
            // only the list would leave ids walkable one at a time.
            "/api/admin/subscribers",
            // Usage, narrowed to the customers above.
            "/api/admin/usage",
            // Their own profile and password. Not "/api/admin/staff", which
            // would let a branch login manage everybody's logins.
            "/api/admin/staff/me",
            // The operator's package list. No customer data in it, and a branch
            // cannot change prices -- PRICING is not among their permissions.
            "/api/admin/plans");

    // Deliberately NOT here, though each was on this list while it was being
    // written: overview, vouchers, tickets, calls, branches, portal settings,
    // notification templates. All of them read customer data that is not yet
    // filtered by branch, and a list that promises more than the code delivers
    // is exactly the partial filter this design exists to avoid. Each becomes a
    // one-line addition the day its query honours the branch.

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && branchOf(auth) != null && isGuardedPath(request)
                && !isAllowed(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            // UTF-8 explicitly, for the same reason DemoReadOnlyFilter does it:
            // the servlet default is ISO-8859-1 and the dash below would go out
            // as a question mark.
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"message\":\"Your login is limited to one branch, and this part of the "
                            + "system is not split by branch yet — so it is closed rather than "
                            + "showing you other branches. Ask head office.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The branch on this session, or null for head office. */
    static Long branchOf(Authentication auth) {
        for (var authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith("BRANCH_")) {
                try {
                    return Long.valueOf(value.substring("BRANCH_".length()));
                } catch (NumberFormatException notANumber) {
                    // An authority we cannot parse must not read as head office.
                    // Returning a branch id nothing matches fails closed.
                    return -1L;
                }
            }
        }
        return null;
    }

    private static boolean isAllowed(String uri) {
        for (String prefix : ALLOWED) {
            if (uri.equals(prefix) || uri.startsWith(prefix + "/") || uri.startsWith(prefix + "?")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGuardedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/api/admin");
    }
}
