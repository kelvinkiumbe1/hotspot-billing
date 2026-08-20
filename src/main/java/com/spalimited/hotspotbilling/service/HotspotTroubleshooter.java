package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why one customer cannot get online.
 *
 * <p>The support call is always the same: "my code does not work". There are
 * eight or nine reasons that can be true and they live in different places --
 * the database, the router's user list, its active sessions, its MAC bindings,
 * the plan's profile, the router being up at all. Somebody answering the phone
 * checks two of them, guesses, and issues a replacement code that fails for the
 * same reason.
 *
 * <p>So this checks all of them in order and says which one is wrong. Each answer
 * is a sentence somebody can read down the phone rather than a status code.
 *
 * <h2>Unknown is a real answer</h2>
 *
 * <p>A check that could not run reports UNKNOWN rather than passing or failing.
 * A router that is unreachable makes five of these checks impossible, and
 * reporting them as failures would send an operator hunting for a provisioning
 * problem that does not exist -- the honest answer is "the router is down, and
 * nothing below could be checked".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HotspotTroubleshooter {

    /** How one check came out. */
    public enum Verdict { OK, PROBLEM, WARN, UNKNOWN }

    /**
     * One check.
     *
     * @param fix what to do about it, or null when there is nothing to do
     */
    public record Check(String name, Verdict verdict, String detail, String fix) {
    }

    private final VoucherRepository vouchers;
    private final MikrotikService mikrotikService;

    /**
     * Everything that could be wrong with one code, in the order worth checking.
     *
     * <p>Ordered so the first PROBLEM is usually the answer. An expired pass makes
     * every later check meaningless, so it is asked before anything that needs the
     * router.
     */
    @Transactional
    public Map<String, Object> diagnose(String code, String mac) {
        List<Check> checks = new ArrayList<>();
        String trimmed = code == null ? "" : code.trim();

        Voucher voucher = trimmed.isBlank() ? null
                : vouchers.findByCode(trimmed).orElse(null);

        // 1. Does the code exist at all? A typo is the commonest cause by a wide
        // margin and costs nothing to rule out.
        if (voucher == null) {
            checks.add(new Check("The code exists", Verdict.PROBLEM,
                    trimmed.isBlank() ? "No code was given."
                            : "No pass in the system has the code " + trimmed + ".",
                    "Check the code was read correctly — 0 and O, 1 and I are the usual "
                            + "pair. If it is right, the pass was never issued."));
            return render(checks, null);
        }
        checks.add(new Check("The code exists", Verdict.OK,
                "Issued " + (voucher.getCreatedAt() == null ? "at an unknown time"
                        : "on " + voucher.getCreatedAt()) + ".", null));

        // 2. Has it been paid for and not yet used up?
        checks.add(statusCheck(voucher));
        checks.add(expiryCheck(voucher));
        checks.add(dataCheck(voucher));

        // 3. The router. Everything below needs it, so a failure here is the end
        // of the useful answer rather than one item in a list.
        Router router = null;
        try {
            router = mikrotikService.routerFor(voucher.getRouterId());
        } catch (Exception e) {
            log.debug("No router resolvable for voucher {}: {}", trimmed, e.getMessage());
        }
        if (router == null || !mikrotikService.manageable(router)) {
            checks.add(new Check("The router can be reached", Verdict.UNKNOWN,
                    router == null ? "No router is configured for this pass."
                            : router.getName() + " is switched off in settings.",
                    "Nothing below this line could be checked."));
            return render(checks, voucher);
        }

        Map<String, Object> live;
        try {
            live = mikrotikService.hotspotUserState(router, trimmed, mac);
        } catch (Exception e) {
            checks.add(new Check("The router can be reached", Verdict.PROBLEM,
                    "Could not reach " + router.getName() + ": " + e.getMessage(),
                    "Nothing below this line could be checked. Fix the router first — "
                            + "everything else here is guesswork until it answers."));
            return render(checks, voucher);
        }
        checks.add(new Check("The router can be reached", Verdict.OK,
                router.getName() + " answered.", null));

        // 4. Is the code actually on the router? This is the one that catches a
        // pass sold while the router was down.
        boolean present = Boolean.TRUE.equals(live.get("userExists"));
        checks.add(present
                ? new Check("The code is on the router", Verdict.OK,
                        "Found in the hotspot user list.", null)
                : new Check("The code is on the router", Verdict.PROBLEM,
                        "The pass exists here but not on " + router.getName() + ".",
                        "Push it again from the pass's page. This happens when a code is "
                                + "sold while the router is unreachable."));

        // 5. Is somebody already using it, and is that somebody this customer?
        String activeMac = (String) live.get("activeMac");
        if (activeMac != null && !activeMac.isBlank()) {
            boolean sameDevice = mac != null && !mac.isBlank()
                    && activeMac.equalsIgnoreCase(mac.trim());
            checks.add(sameDevice
                    ? new Check("Who is using it", Verdict.OK,
                            "This device is logged in right now.", null)
                    : new Check("Who is using it", Verdict.WARN,
                            "Already in use by " + activeMac + ".",
                            "One pass, one device at a time. Either that is the customer's "
                                    + "other phone, or the code has been shared."));
        } else {
            checks.add(new Check("Who is using it", Verdict.OK,
                    "Nobody is logged in on it.", null));
        }

        // 6. A MAC binding is the reason that is impossible to guess from the
        // outside: the code is fine, the router is fine, and the device is
        // refused because it is tied to a different pass.
        String boundTo = (String) live.get("macBoundTo");
        if (boundTo != null && !boundTo.isBlank()) {
            checks.add(new Check("This device is not tied to another pass", Verdict.PROBLEM,
                    "This device is bound to " + boundTo + ".",
                    "Remove the binding, or have them use the pass it is bound to. "
                            + "Nothing about the code itself will fix this."));
        } else if (mac != null && !mac.isBlank()) {
            checks.add(new Check("This device is not tied to another pass", Verdict.OK,
                    "No binding in the way.", null));
        }

        // 7. The profile. A missing one means the router rejects the login with
        // an error the customer reads as "wrong password".
        boolean profileOk = Boolean.TRUE.equals(live.get("profileExists"));
        if (voucher.getPlan() != null) {
            checks.add(profileOk
                    ? new Check("The plan's profile exists", Verdict.OK,
                            "Found on the router.", null)
                    : new Check("The plan's profile exists", Verdict.PROBLEM,
                            "The profile for " + voucher.getPlan().getName()
                                    + " is missing on " + router.getName() + ".",
                            "Push any pass on this plan to recreate it. Without it the "
                                    + "router refuses the login and the customer sees "
                                    + "something that reads like a wrong password."));
        }

        return render(checks, voucher);
    }

    private Check statusCheck(Voucher v) {
        if (v.getStatus() == Voucher.Status.EXPIRED) {
            return new Check("The pass is still valid", Verdict.PROBLEM,
                    "It is marked expired.",
                    "Sell them a new one, or extend this pass if it expired unfairly.");
        }
        if (v.getStatus() == Voucher.Status.UNUSED) {
            // Not a problem: a pass that has never been logged into is exactly
            // what a customer holds before their first connection.
            return new Check("The pass is still valid", Verdict.OK,
                    "Never used yet — the clock starts at first login.", null);
        }
        return new Check("The pass is still valid", Verdict.OK,
                "Status is " + v.getStatus() + ".", null);
    }

    private Check expiryCheck(Voucher v) {
        if (v.getExpiresAt() == null) {
            return new Check("It has time left", Verdict.OK,
                    "Not started yet — the clock begins at first login.", null);
        }
        if (v.getExpiresAt().isBefore(Instant.now())) {
            return new Check("It has time left", Verdict.PROBLEM,
                    "Ran out on " + v.getExpiresAt() + ".",
                    "The time is gone rather than the data. A new pass is the only fix.");
        }
        return new Check("It has time left", Verdict.OK,
                "Good until " + v.getExpiresAt() + ".", null);
    }

    private Check dataCheck(Voucher v) {
        if (v.getPlan() == null || v.getPlan().getDataLimitMb() == null
                || v.getPlan().getDataLimitMb() <= 0) {
            return new Check("It has data left", Verdict.OK, "This plan has no data cap.", null);
        }
        long capBytes = (long) v.getPlan().getDataLimitMb() * 1024L * 1024L;
        if (v.getUsedBytes() >= capBytes) {
            return new Check("It has data left", Verdict.PROBLEM,
                    "Used " + (v.getUsedBytes() / 1048576) + "MB of "
                            + v.getPlan().getDataLimitMb() + "MB.",
                    "The allowance is spent. A new pass, or a top-up if you sell one.");
        }
        return new Check("It has data left", Verdict.OK,
                (v.getUsedBytes() / 1048576) + "MB of " + v.getPlan().getDataLimitMb()
                        + "MB used.", null);
    }

    /**
     * The verdict, plus the first thing worth doing.
     *
     * <p>A summary is the point of the whole exercise: somebody on the phone
     * wants one sentence, not nine.
     */
    private Map<String, Object> render(List<Check> checks, Voucher voucher) {
        Check firstProblem = checks.stream()
                .filter(c -> c.verdict() == Verdict.PROBLEM)
                .findFirst().orElse(null);
        Check firstWarn = checks.stream()
                .filter(c -> c.verdict() == Verdict.WARN)
                .findFirst().orElse(null);

        String summary;
        if (firstProblem != null) {
            summary = firstProblem.name() + ": " + firstProblem.detail();
        } else if (firstWarn != null) {
            summary = firstWarn.name() + ": " + firstWarn.detail();
        } else if (checks.stream().anyMatch(c -> c.verdict() == Verdict.UNKNOWN)) {
            summary = "Everything that could be checked looks right, but the router could "
                    + "not be reached — so the answer may still be on it.";
        } else {
            // The genuinely awkward outcome, and worth naming rather than
            // implying the tool failed.
            summary = "Everything checks out. If they still cannot get on, it is between "
                    + "their device and the access point — signal, a captive-portal page "
                    + "cached in their browser, or a phone with mobile data still on.";
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Check c : checks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", c.name());
            row.put("verdict", c.verdict());
            row.put("detail", c.detail());
            row.put("fix", c.fix());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("checks", rows);
        out.put("problems", checks.stream().filter(c -> c.verdict() == Verdict.PROBLEM).count());
        if (voucher != null) {
            out.put("voucher", Map.of(
                    "code", voucher.getCode(),
                    "status", voucher.getStatus(),
                    "plan", voucher.getPlan() == null ? "" : voucher.getPlan().getName(),
                    "phoneNumber", voucher.getPhoneNumber() == null ? "" : voucher.getPhoneNumber()));
        }
        return out;
    }
}
