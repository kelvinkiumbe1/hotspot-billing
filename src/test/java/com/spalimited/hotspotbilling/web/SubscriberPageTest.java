package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.service.BranchScope;
import com.spalimited.hotspotbilling.service.FupService;
import com.spalimited.hotspotbilling.service.SubscriberProvisioningService;
import com.spalimited.hotspotbilling.service.SubscriberUsageService;
import com.spalimited.hotspotbilling.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paging the customer list.
 *
 * <p>The list endpoint returned every customer as a full entity — 4.1 MB of JSON
 * at five thousand of them, and the slowest thing in the product. Paging it is
 * only safe if two things hold, and both are here:
 *
 * <p>The branch has to be part of the <em>query</em>. Narrowing a page the
 * database has already chosen gives a branch login a half-empty page, a wrong
 * total, and a page two that silently skips customers rather than showing them —
 * a data leak in reverse, and much harder to notice than showing too much.
 *
 * <p>And the totals on the stat cards have to come from the whole book, not the
 * page, or an operator reads their monthly revenue off fifty customers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriberPageTest {

    @Mock private SubscriberRepository subscribers;
    @Mock private SubscriptionPaymentRepository payments;
    @Mock private SubscriptionService subscriptionService;
    @Mock private SubscriberUsageService subscriberUsage;
    @Mock private FupService fupService;
    @Mock private BranchScope branchScope;
    @Mock private SubscriberProvisioningService provisioning;

    @InjectMocks
    private SubscriberController controller;

    @BeforeEach
    void setUp() {
        when(branchScope.current()).thenReturn(null);
        when(subscribers.search(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        when(subscribers.countAndValueByStatus(any())).thenReturn(List.of());
        when(subscribers.countExpiringBefore(any(), any())).thenReturn(0L);
        when(subscribers.lookup(any())).thenReturn(List.of());
    }

    private Subscriber person(long id, String name, Subscriber.Status status) {
        return Subscriber.builder().id(id).fullName(name).phoneNumber("254700000" + id)
                .pppoeUsername("user" + id).monthlyFee(new BigDecimal("2500"))
                .status(status).paidUntil(Instant.now().plusSeconds(86400)).build();
    }

    // --- the branch, which must reach the database ---

    @Test
    @DisplayName("a branch login's branch goes into the query, not a filter afterwards")
    void branchIsPartOfTheQuery() {
        when(branchScope.current()).thenReturn(7L);

        controller.page(0, 50, null, null);

        // If this were filtered after the fact, page two would skip customers
        // rather than show them.
        verify(subscribers).search(eq(7L), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("head office asks for no branch at all")
    void headOfficeIsUnrestricted() {
        controller.page(0, 50, null, null);

        verify(subscribers).search(isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("the summary is counted for the same branch as the page")
    void summaryIsScopedToo() {
        when(branchScope.current()).thenReturn(7L);

        controller.page(0, 50, null, null);

        // A branch login reading head office's revenue would be the same leak by
        // another route.
        verify(subscribers).countAndValueByStatus(7L);
        verify(subscribers).countExpiringBefore(eq(7L), any());
    }

    @Test
    @DisplayName("the picker list is scoped to the branch as well")
    void lookupIsScoped() {
        when(branchScope.current()).thenReturn(7L);

        controller.lookup();

        verify(subscribers).lookup(7L);
    }

    // --- paging ---

    @Test
    @DisplayName("a page carries its position and the total, so the UI can count")
    void pageReportsItsPlace() {
        when(subscribers.search(any(), any(), any(), any())).thenReturn(
                new PageImpl<>(List.of(person(1, "Mary", Subscriber.Status.ACTIVE)),
                        PageRequest.of(2, 50), 3000));

        Map<String, Object> out = controller.page(2, 50, null, null);

        assertThat(out.get("page")).isEqualTo(2);
        assertThat(out.get("total")).isEqualTo(3000L);
        assertThat(out.get("totalPages")).isEqualTo(60);
        assertThat((List<?>) out.get("content")).hasSize(1);
    }

    @Test
    @DisplayName("an absurd page size is clamped rather than honoured")
    void pageSizeIsBounded() {
        controller.page(0, 100000, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(subscribers).search(any(), any(), any(), pageable.capture());
        // Otherwise ?size=100000 asks for the whole book back and undoes the
        // point of the endpoint.
        assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("a negative page is the first page, not an error")
    void negativePageIsClamped() {
        controller.page(-5, 50, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(subscribers).search(any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    // --- searching ---

    @Test
    @DisplayName("a search term is lower-cased and wrapped, and bound as a parameter")
    void searchTermIsPrepared() {
        controller.page(0, 50, "  MARY  ", null);

        verify(subscribers).search(isNull(), isNull(), eq("%mary%"), any());
    }

    @Test
    @DisplayName("an empty search is no search, not a search for nothing")
    void blankSearchIsIgnored() {
        controller.page(0, 50, "   ", null);

        verify(subscribers).search(isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("a status filter is passed through, and ALL means no filter")
    void statusFilter() {
        controller.page(0, 50, null, "suspended");
        verify(subscribers).search(isNull(), eq(Subscriber.Status.SUSPENDED), isNull(), any());

        controller.page(0, 50, null, "ALL");
        verify(subscribers).search(isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("a status nobody has is refused rather than quietly ignored")
    void unknownStatusIsRefused() {
        assertThatThrownBy(() -> controller.page(0, 50, null, "PENDING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown status");
    }

    // --- the figures ---

    @Test
    @DisplayName("the stat cards count the whole book, not the page")
    void summaryCoversEverything() {
        when(subscribers.search(any(), any(), any(), any())).thenReturn(
                new PageImpl<>(List.of(person(1, "Mary", Subscriber.Status.ACTIVE)),
                        PageRequest.of(0, 50), 3000));
        when(subscribers.countAndValueByStatus(any())).thenReturn(List.of(
                new Object[] { Subscriber.Status.ACTIVE, 2400L, new BigDecimal("6000000") },
                new Object[] { Subscriber.Status.SUSPENDED, 600L, new BigDecimal("1500000") }));
        when(subscribers.countExpiringBefore(any(), any())).thenReturn(37L);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary =
                (Map<String, Object>) controller.page(0, 50, null, null).get("summary");

        // Read off the page instead and an operator sees the revenue of fifty
        // customers and thinks it is the business.
        assertThat(summary.get("active")).isEqualTo(2400L);
        assertThat(summary.get("suspended")).isEqualTo(600L);
        assertThat(summary.get("expiringSoon")).isEqualTo(37L);
        assertThat((BigDecimal) summary.get("monthlyRevenue")).isEqualByComparingTo("6000000");
    }

    @Test
    @DisplayName("monthly revenue counts the customers who are actually on")
    void revenueExcludesSuspended() {
        when(subscribers.countAndValueByStatus(any())).thenReturn(List.of(
                new Object[] { Subscriber.Status.SUSPENDED, 600L, new BigDecimal("1500000") },
                new Object[] { Subscriber.Status.ACTIVE, 10L, new BigDecimal("25000") }));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary =
                (Map<String, Object>) controller.page(0, 50, null, null).get("summary");

        // Billing somebody who is cut off is not revenue.
        assertThat((BigDecimal) summary.get("monthlyRevenue")).isEqualByComparingTo("25000");
    }

    @Test
    @DisplayName("an empty book reports zeroes rather than nothing at all")
    void emptyBook() {
        @SuppressWarnings("unchecked")
        Map<String, Object> summary =
                (Map<String, Object>) controller.page(0, 50, null, null).get("summary");

        assertThat(summary.get("active")).isEqualTo(0L);
        assertThat(summary.get("suspended")).isEqualTo(0L);
        assertThat((BigDecimal) summary.get("monthlyRevenue")).isEqualByComparingTo("0");
    }

    // --- the picker ---

    @Test
    @DisplayName("the picker returns seven fields, not the whole customer")
    void lookupIsSlim() {
        when(subscribers.lookup(any())).thenReturn(List.<Object[]>of(
                new Object[] { 1L, "Mary Kamau", "254712345678", "mkamau",
                        Subscriber.Status.ACTIVE, new BigDecimal("2500"), 3L }));

        Map<String, Object> row = controller.lookup().get(0);

        // Seven, not thirty-five: the five a picker needs, plus the fee two
        // screens price against and the router the fleet screen groups by.
        assertThat(row).containsOnlyKeys("id", "fullName", "phoneNumber", "pppoeUsername",
                "status", "monthlyFee", "routerId");
        assertThat(row.get("fullName")).isEqualTo("Mary Kamau");
    }
}
