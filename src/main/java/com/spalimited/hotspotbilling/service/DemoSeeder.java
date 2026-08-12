package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Populates a read-only demo instance so a prospecting ISP sees a busy,
 * believable business instead of empty screens. Runs only when demo.enabled
 * is true (a dedicated demo deployment) and only once — it keys off the "demo"
 * login existing. A real ISP deployment never turns this on, so no sample data
 * or read-only account can appear in their database.
 *
 * <p>Dashboards compute live from stored rows, so seeding payments, vouchers,
 * subscribers, expenses, leads and traffic is all that's needed to make every
 * chart fill in. Runs late (@Order) so the starter plans exist first.
 */
@Component
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
@Order(100)
@RequiredArgsConstructor
@Slf4j
public class DemoSeeder implements ApplicationRunner {

    private final StaffUserRepository staff;
    private final PlanRepository plans;
    private final VoucherRepository vouchers;
    private final PaymentRepository payments;
    private final SubscriberRepository subscribers;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final ExpenseRepository expenses;
    private final LeadRepository leads;
    private final RouterRepository routers;
    private final TrafficUsageRepository traffic;
    private final PasswordEncoder encoder;

    // Deterministic so a re-seed (fresh DB) always tells the same story.
    private final Random rnd = new Random(42);
    private final ZoneId zone = ZoneId.systemDefault();

    private static final String[] FIRST = {"Amina", "Brian", "Cynthia", "David", "Esther", "Felix",
            "Grace", "Hassan", "Irene", "James", "Kevin", "Lucy", "Mercy", "Njoroge", "Otieno",
            "Peter", "Quresh", "Ruth", "Samuel", "Teresa", "Umar", "Violet", "Wanjiku", "Yusuf", "Zawadi"};
    private static final String[] LAST = {"Mwangi", "Ochieng", "Kamau", "Wafula", "Chebet", "Mutua",
            "Njoroge", "Abdi", "Kiptoo", "Owino", "Njeri", "Barasa", "Mumo", "Kirui", "Achieng"};
    private static final String[] SITES = {"Westlands", "Kasarani", "Rongai", "Nyali", "Milimani", "CBD"};

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (staff.findByUsername("demo").isPresent()) {
            return; // already seeded
        }
        log.info("Seeding read-only demo data…");

        staff.save(StaffUser.builder()
                .username("demo")
                .passwordHash(encoder.encode(UUID.randomUUID().toString())) // no usable password
                .fullName("Demo Operator")
                .role(StaffUser.Role.OWNER)
                .active(true).seeded(true).demo(true)
                .build());

        List<Plan> planList = ensurePlans();
        List<Router> routerList = seedRouters();
        seedHotspot(planList, routerList);
        seedSubscribers(planList);
        seedExpenses();
        seedLeads();
        seedTraffic(planList, routerList);

        log.info("Demo data seeded ({} plans, {} vouchers, {} payments, {} subscribers)",
                planList.size(), vouchers.count(), payments.count(), subscribers.count());
    }

    private List<Plan> ensurePlans() {
        List<Plan> existing = plans.findAll();
        if (!existing.isEmpty()) {
            return existing;
        }
        // A fresh demo DB with no starter plans — make a small hotspot ladder.
        int[][] specs = {{5, 60}, {10, 180}, {20, 1440}, {50, 10080}}; // {price, minutes}
        String[] names = {"1 Hour", "3 Hours", "1 Day", "1 Week"};
        List<Plan> made = new ArrayList<>();
        for (int i = 0; i < specs.length; i++) {
            made.add(plans.save(Plan.builder()
                    .name(names[i]).price(BigDecimal.valueOf(specs[i][0]))
                    .durationMinutes(specs[i][1]).bandwidth("5M/5M")
                    .type(Plan.Type.HOTSPOT).build()));
        }
        return made;
    }

    private List<Router> seedRouters() {
        List<Router> made = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            made.add(routers.save(Router.builder()
                    .name(SITES[i] + " Site").location(SITES[i])
                    .host("10.90.0." + (2 + i)).port(8728).username("admin")
                    .useSsl(false).enabled(true).online(true).defaultRouter(i == 0)
                    .uptime("2w3d4h").routerOsVersion("7.15.3").boardName("RB4011")
                    .activeHotspotUsers(12 + rnd.nextInt(30)).activePppoeUsers(8 + rnd.nextInt(15))
                    .lastSeenAt(Instant.now()).lastCheckedAt(Instant.now())
                    .build()));
        }
        return made;
    }

    /** 30 days of hotspot sales: success payments + their vouchers, plus stock. */
    private void seedHotspot(List<Plan> planList, List<Router> routerList) {
        Instant now = Instant.now();
        for (int day = 29; day >= 0; day--) {
            int sales = 3 + rnd.nextInt(9); // 3–11 sales a day
            for (int s = 0; s < sales; s++) {
                Plan plan = planList.get(rnd.nextInt(planList.size()));
                Instant when = now.minus(Duration.ofDays(day))
                        .minus(Duration.ofMinutes(rnd.nextInt(1440)));
                String phone = phone();
                Voucher v = vouchers.save(Voucher.builder()
                        .code(code()).plan(plan).phoneNumber(phone)
                        .status(Voucher.Status.ACTIVE)
                        .createdAt(when).activatedAt(when)
                        .routerId(routerList.get(rnd.nextInt(routerList.size())).getId())
                        .build());
                payments.save(Payment.builder()
                        .phoneNumber(phone).amount(plan.getPrice()).plan(plan)
                        .status(Payment.Status.SUCCESS)
                        .mpesaReceiptNumber(receipt())
                        .createdAt(when).completedAt(when).voucher(v)
                        .build());
            }
        }
        // Unsold stock, so the voucher sell-through card has a denominator.
        for (int i = 0; i < 25; i++) {
            Plan plan = planList.get(rnd.nextInt(planList.size()));
            vouchers.save(Voucher.builder()
                    .code(code()).plan(plan).status(Voucher.Status.UNUSED)
                    .createdAt(now.minus(Duration.ofDays(rnd.nextInt(20))))
                    .build());
        }
    }

    /** PPPoE home customers with a few months of subscription payments each. */
    private void seedSubscribers(List<Plan> planList) {
        for (int i = 0; i < 24; i++) {
            String name = name();
            BigDecimal fee = BigDecimal.valueOf(1500 + 500L * rnd.nextInt(6)); // 1500–4000
            boolean suspended = rnd.nextInt(6) == 0;
            Subscriber sub = subscribers.save(Subscriber.builder()
                    .fullName(name).phoneNumber(phone())
                    .pppoeUsername("user" + (1000 + i))
                    .pppoePassword(UUID.randomUUID().toString().substring(0, 8))
                    .bandwidth((5 + 5 * rnd.nextInt(4)) + "M/" + (5 + 5 * rnd.nextInt(4)) + "M")
                    .monthlyFee(fee)
                    .status(suspended ? Subscriber.Status.SUSPENDED : Subscriber.Status.ACTIVE)
                    .paidUntil(Instant.now().plus(Duration.ofDays(suspended ? -5 : 5 + rnd.nextInt(25))))
                    .lastSeenOnlineAt(suspended ? null : Instant.now().minus(Duration.ofMinutes(rnd.nextInt(600))))
                    .build());
            int months = 2 + rnd.nextInt(6);
            for (int m = months; m >= 1; m--) {
                Instant when = Instant.now().minus(Duration.ofDays(30L * m));
                subscriptionPayments.save(SubscriptionPayment.builder()
                        .subscriber(sub).amount(fee).months(1)
                        .method(rnd.nextInt(4) == 0 ? SubscriptionPayment.Method.CASH : SubscriptionPayment.Method.MPESA)
                        .status(SubscriptionPayment.Status.SUCCESS)
                        .completedAt(when)
                        .build());
            }
        }
    }

    private void seedExpenses() {
        Expense.Category[] cats = Expense.Category.values();
        String[] notes = {"Upstream bandwidth", "Router replacement", "Office rent", "Staff salaries",
                "Fuel & transport", "Generator diesel", "SIM & licences", "Facebook ads"};
        for (int i = 0; i < 14; i++) {
            expenses.save(Expense.builder()
                    .description(notes[i % notes.length])
                    .category(cats[rnd.nextInt(cats.length)])
                    .amount(BigDecimal.valueOf(2000 + 1000L * rnd.nextInt(40)))
                    .incurredOn(LocalDate.now(zone).minusDays(rnd.nextInt(30)))
                    .recordedBy("demo")
                    .build());
        }
    }

    private void seedLeads() {
        Lead.Source[] sources = Lead.Source.values();
        Lead.Status[] statuses = Lead.Status.values();
        for (int i = 0; i < 12; i++) {
            leads.save(Lead.builder()
                    .fullName(name()).phoneNumber(phone())
                    .location(SITES[rnd.nextInt(SITES.length)])
                    .interestedIn((5 + 5 * rnd.nextInt(4)) + " Mbps home")
                    .source(sources[rnd.nextInt(sources.length)])
                    .status(statuses[rnd.nextInt(statuses.length)])
                    .build());
        }
    }

    /** Hourly traffic rows so the data-usage reports and heatmap fill in. */
    private void seedTraffic(List<Plan> planList, List<Router> routerList) {
        Instant now = Instant.now();
        for (int day = 13; day >= 0; day--) {
            for (int hour = 6; hour <= 23; hour++) {
                if (rnd.nextInt(3) == 0) continue; // gaps make the heatmap realistic
                int users = 1 + rnd.nextInt(8);
                for (int u = 0; u < users; u++) {
                    Instant bucket = now.minus(Duration.ofDays(day)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                            .minus(Duration.ofHours(23 - hour));
                    Plan plan = planList.get(rnd.nextInt(planList.size()));
                    long down = (50L + rnd.nextInt(400)) * 1024 * 1024;
                    long up = down / (4 + rnd.nextInt(4));
                    traffic.save(TrafficUsage.builder()
                            .bucketHour(bucket)
                            .routerId(routerList.get(rnd.nextInt(routerList.size())).getId())
                            .userKey(code())
                            .planId(plan.getId())
                            .bytesUp(up).bytesDown(down)
                            .build());
                }
            }
        }
    }

    private String phone() {
        return "2547" + (10_000_000 + rnd.nextInt(89_999_999));
    }

    private String name() {
        return FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)];
    }

    private static final char[] ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private String code() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(ALNUM[rnd.nextInt(ALNUM.length)]);
        return sb.toString();
    }

    private String receipt() {
        StringBuilder sb = new StringBuilder("D");
        for (int i = 0; i < 9; i++) sb.append(ALNUM[rnd.nextInt(ALNUM.length)]);
        return sb.toString();
    }
}
