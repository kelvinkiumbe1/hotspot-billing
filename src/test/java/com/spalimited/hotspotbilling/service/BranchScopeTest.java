package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.config.BranchScopeFilter;
import com.spalimited.hotspotbilling.domain.Subscriber;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Keeping a branch login inside its own branch.
 *
 * <p>This is a security boundary, so the tests are written the way somebody
 * attacking it would think: not "does my own list look right" but "what can I
 * reach that is not mine". The two failures that matter are a customer from
 * another branch being readable by walking ids, and an endpoint nobody
 * remembered to filter quietly serving the whole network.
 *
 * <p>The second is why the filter is an allowlist. A test cannot prove that
 * sixty endpoints all filter correctly; it can prove that anything not on a
 * short list is refused outright, which is a claim worth much more.
 */
class BranchScopeTest {

    private final BranchScope scope = new BranchScope();
    private final BranchScopeFilter filter = new BranchScopeFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Signs in as head office: every permission, no branch. */
    private void asHeadOffice() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("CUSTOMERS"))));
    }

    /** Signs in as a partner limited to one branch. */
    private void asBranch(long branchId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("partner", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("CUSTOMERS"),
                                new SimpleGrantedAuthority("BRANCH_" + branchId))));
    }

    private static Subscriber sub(long id, Long branchId) {
        return Subscriber.builder().id(id).fullName("Customer " + id)
                .pppoeUsername("user" + id).branchId(branchId).build();
    }

    // --- Reading the scope ---

    @Test
    @DisplayName("head office has no branch and sees everything")
    void headOfficeIsUnrestricted() {
        asHeadOffice();

        assertThat(scope.current()).isNull();
        assertThat(scope.isRestricted()).isFalse();
        assertThat(scope.filter(List.of(sub(1, 5L), sub(2, 6L), sub(3, null)))).hasSize(3);
    }

    @Test
    @DisplayName("a branch login sees only its own customers")
    void branchSeesItsOwn() {
        asBranch(5);

        List<Subscriber> visible = scope.filter(List.of(sub(1, 5L), sub(2, 6L), sub(3, 5L)));

        assertThat(visible).extracting(Subscriber::getId).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("an unfiled customer belongs to head office, not to everybody")
    void unassignedStaysWithHeadOffice() {
        asBranch(5);

        List<Subscriber> visible = scope.filter(List.of(sub(1, 5L), sub(2, null)));

        // Defaulting the other way would show every partner every customer
        // nobody had got round to filing -- which on a fresh install is all of
        // them.
        assertThat(visible).extracting(Subscriber::getId).containsExactly(1L);
    }

    @Test
    @DisplayName("no authentication at all is treated as head office, not as a branch")
    void noAuthIsNotABranch() {
        // Scheduled jobs and startup code run with no security context. Treating
        // that as a branch would silently stop every sweep from seeing anybody.
        assertThat(scope.current()).isNull();
        assertThat(scope.filter(List.of(sub(1, 5L)))).hasSize(1);
    }

    @Test
    @DisplayName("an unparseable branch authority matches nobody rather than everybody")
    void corruptAuthorityFailsClosed() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("odd", null,
                        List.of(new SimpleGrantedAuthority("BRANCH_notanumber"))));

        // The dangerous reading of a broken authority is "no branch", i.e. head
        // office. -1 matches no branch, so they see nothing instead.
        assertThat(scope.current()).isEqualTo(-1L);
        assertThat(scope.filter(List.of(sub(1, 5L), sub(2, null)))).isEmpty();
    }

    // --- Reaching one customer by id ---

    @Test
    @DisplayName("a branch login cannot reach another branch's customer by id")
    void cannotWalkIds() {
        asBranch(5);

        assertThat(scope.mayReach(sub(1, 5L))).isTrue();
        assertThat(scope.mayReach(sub(2, 6L))).isFalse();
        // Filtering only the list would leave this open: ask for id 2, 3, 4...
        assertThatThrownBy(() -> scope.require(sub(2, 6L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the refusal does not admit the customer exists")
    void refusalDoesNotLeakExistence() {
        asBranch(5);

        assertThatThrownBy(() -> scope.require(sub(2, 6L)))
                .hasMessage("No such customer");
        // "Not your customer" would confirm that customer 4112 exists and belongs
        // to a competitor. Small leak, free to avoid.
        assertThatThrownBy(() -> scope.require(null))
                .hasMessage("No such customer");
    }

    @Test
    @DisplayName("head office can reach anybody")
    void headOfficeReachesAnybody() {
        asHeadOffice();

        assertThat(scope.mayReach(sub(1, 5L))).isTrue();
        assertThat(scope.mayReach(sub(2, null))).isTrue();
        scope.require(sub(2, 99L));
    }

    // --- The filter ---

    private MockHttpServletResponse pass(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        response.setCommitted(true);
        // Whether the request got through is what the caller asserts on.
        if (response.getStatus() == 200) {
            verify(chain).doFilter(request, response);
        } else {
            verify(chain, never()).doFilter(request, response);
        }
        return response;
    }

    @Test
    @DisplayName("head office reaches everything")
    void filterLetsHeadOfficeThrough() throws Exception {
        asHeadOffice();

        assertThat(pass("/api/admin/overview").getStatus()).isEqualTo(200);
        assertThat(pass("/api/admin/router-backups").getStatus()).isEqualTo(200);
        assertThat(pass("/api/admin/bank/queue").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a branch login reaches the endpoints that honour its branch")
    void filterAllowsBranchAwarePaths() throws Exception {
        asBranch(5);

        assertThat(pass("/api/admin/subscribers").getStatus()).isEqualTo(200);
        assertThat(pass("/api/admin/subscribers/42/usage").getStatus()).isEqualTo(200);
        assertThat(pass("/api/admin/usage/top").getStatus()).isEqualTo(200);
        assertThat(pass("/api/admin/staff/me").getStatus()).isEqualTo(200);
        assertThat(pass("/api/admin/plans").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a branch login is refused everything that is not split by branch")
    void filterRefusesEverythingElse() throws Exception {
        asBranch(5);

        // Each of these reads customer data across the whole network. Being
        // refused is the point: an unfiltered screen would show a partner the
        // other partners' customers.
        for (String uri : List.of("/api/admin/overview", "/api/admin/tickets",
                "/api/admin/vouchers", "/api/admin/calls", "/api/admin/bank/queue",
                "/api/admin/router-backups", "/api/admin/billing-documents/quotes",
                "/api/admin/analytics", "/api/admin/audit", "/api/admin/settings/mikrotik")) {
            assertThat(pass(uri).getStatus())
                    .withFailMessage("expected %s to be refused for a branch login", uri)
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("a branch login cannot reach the staff list by leaning on the /me prefix")
    void staffPrefixIsNotOpen() throws Exception {
        asBranch(5);

        // "/api/admin/staff/me" must not make "/api/admin/staff" reachable, or a
        // partner could read -- and with STAFF, change -- everybody's logins.
        assertThat(pass("/api/admin/staff").getStatus()).isEqualTo(403);
        assertThat(pass("/api/admin/staff/7").getStatus()).isEqualTo(403);
        assertThat(pass("/api/admin/staff/me").getStatus()).isEqualTo(200);
        assertThat(pass("/api/admin/staff/me/password").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a lookalike path is not mistaken for an allowed one")
    void prefixesAreNotSubstrings() throws Exception {
        asBranch(5);

        // "/api/admin/subscribers" must not open "/api/admin/subscribers-export"
        // or anything else that merely starts with the same letters.
        assertThat(pass("/api/admin/subscribers-export").getStatus()).isEqualTo(403);
        assertThat(pass("/api/admin/plansomething").getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("the refusal explains itself rather than looking like a bug")
    void refusalIsExplained() throws Exception {
        asBranch(5);

        MockHttpServletResponse response = pass("/api/admin/overview");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("limited to one branch");
        // UTF-8 declared, so the dash in the message is not sent as a question
        // mark -- the mistake DemoReadOnlyFilter already had to fix once.
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
    }

    @Test
    @DisplayName("public and technician paths are left alone")
    void nonAdminPathsAreUntouched() throws Exception {
        asBranch(5);

        // Signing in happens under /api/auth, so locking it would lock the
        // branch login out of its own account.
        assertThat(pass("/api/auth/login").getStatus()).isEqualTo(200);
        assertThat(pass("/api/portal/plans").getStatus()).isEqualTo(200);
    }
}
