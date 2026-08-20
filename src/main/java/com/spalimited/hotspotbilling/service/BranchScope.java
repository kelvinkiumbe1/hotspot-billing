package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Subscriber;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Which branch the person making this request belongs to, if any.
 *
 * <p>Null means head office and sees everything, which is what every login had
 * before branches were scoped at all. A branch id is only ever a restriction.
 *
 * <p>Read from the security context rather than passed down through method
 * signatures. That is the less pure choice and the safer one here: threading a
 * branch id through every service call means every new call site is a chance to
 * pass null and quietly widen access, whereas a service that asks for the scope
 * gets the truth or nothing.
 */
@Component
public class BranchScope {

    /** The caller's branch, or null for head office. */
    public Long current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith("BRANCH_")) {
                try {
                    return Long.valueOf(value.substring("BRANCH_".length()));
                } catch (NumberFormatException notANumber) {
                    // An unparseable authority must not read as head office. -1
                    // matches no branch, so the caller sees nothing rather than
                    // everything.
                    return -1L;
                }
            }
        }
        return null;
    }

    public boolean isRestricted() {
        return current() != null;
    }

    /**
     * Narrows a list of customers to the caller's branch.
     *
     * <p>Head office gets the list back untouched. A branch login gets only its
     * own, and a customer with no branch set is <em>not</em> included: an
     * unassigned customer belongs to head office until somebody says otherwise,
     * and defaulting the other way would show every partner every customer
     * nobody had got round to filing.
     */
    public List<Subscriber> filter(List<Subscriber> all) {
        Long branch = current();
        if (branch == null) {
            return all;
        }
        return all.stream().filter(s -> branch.equals(s.getBranchId())).toList();
    }

    /**
     * Whether the caller may act on this customer.
     *
     * <p>Called before anything that reads or changes one subscriber by id.
     * Without it, a branch login blocked from the customer <em>list</em> could
     * still walk ids one at a time.
     */
    public boolean mayReach(Subscriber sub) {
        Long branch = current();
        return branch == null || (sub != null && Objects.equals(branch, sub.getBranchId()));
    }

    /**
     * Same check, as a guard.
     *
     * <p>The message deliberately does not distinguish "no such customer" from
     * "not your customer": telling a partner that customer 4,112 exists but is
     * somebody else's is itself a leak, small but free to avoid.
     */
    public void require(Subscriber sub) {
        if (!mayReach(sub)) {
            throw new IllegalArgumentException("No such customer");
        }
    }
}
