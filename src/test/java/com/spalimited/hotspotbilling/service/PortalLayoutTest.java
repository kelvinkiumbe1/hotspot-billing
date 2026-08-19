package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PortalSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Letting an operator rearrange the page without letting them break it.
 *
 * <p>Two things have to hold at once here, and they pull against each other. An
 * operator must be able to move and hide the blocks. And a portal must keep
 * selling — through a release that adds a block, a release that removes one, and
 * a saved row that predates whatever rule is being added today.
 */
class PortalLayoutTest {

    @Test
    @DisplayName("Nothing saved leaves the design exactly as it ships")
    void unsetChangesNothing() {
        // The whole contract of this feature. An operator who never opens the
        // Layout tab must see no difference on a screen that sells things, so
        // every knob is null and the order is the shipping order.
        Map<String, Object> described = PortalLayout.describe(PortalSettings.builder().build());

        assertThat(described.get("order")).isEqualTo(PortalLayout.BLOCKS);
        assertThat(described.get("hidden")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.COLLECTION).isEmpty();
        assertThat(described.get("align")).isNull();
        assertThat(described.get("radius")).isNull();
        assertThat(described.get("logoSize")).isNull();
        assertThat(described.get("headingFont")).isNull();
        assertThat(described.get("density")).isNull();
    }

    @Test
    @DisplayName("The saved order is respected")
    void savedOrderIsUsed() {
        assertThat(PortalLayout.order("voucher,plans,promo,rewards"))
                .containsExactly("voucher", "plans", "promo", "rewards");
    }

    @Test
    @DisplayName("A block this release added appears, at the end rather than not at all")
    void blocksMissingFromASavedOrderStillAppear() {
        // An operator saved their order before "rewards" existed. It has to show
        // up on its own -- a block that needs a re-save to appear is a block
        // nobody discovers.
        assertThat(PortalLayout.order("voucher,plans,promo"))
                .containsExactly("voucher", "plans", "promo", "rewards");
    }

    @Test
    @DisplayName("A block this release removed does not leave a hole")
    void unknownNamesAreDropped() {
        // The mirror case: an operator saved an order naming a block that has
        // since gone. Keeping it would put a gap in the page.
        assertThat(PortalLayout.order("voucher,somethingRemoved,plans,promo,rewards"))
                .containsExactly("voucher", "plans", "promo", "rewards");
    }

    @Test
    @DisplayName("A name repeated in a saved order is not rendered twice")
    void duplicatesAreCollapsed() {
        assertThat(PortalLayout.order("plans,plans,voucher,voucher"))
                .containsExactly("plans", "voucher", "promo", "rewards");
    }

    @Test
    @DisplayName("Whitespace and case in a saved order are forgiven")
    void savedOrderIsForgiving() {
        assertThat(PortalLayout.order(" Voucher , PLANS ,promo, Rewards "))
                .containsExactly("voucher", "plans", "promo", "rewards");
    }

    // ------------------------------------------------------------ what may hide

    @Test
    @DisplayName("The plans cannot be hidden, however they got into the list")
    void plansCanNeverBeHidden() {
        // Not merely rejected at the door. The check lives here because it has to
        // hold for a row written before the rule existed, by a direct database
        // edit, or by a future bug in the form -- and the portal asks this method.
        assertThat(PortalLayout.hidden("plans")).isEmpty();
        assertThat(PortalLayout.hidden("plans,rewards")).containsExactly("rewards");
        assertThat(PortalLayout.cleanHidden(List.of("plans"))).isNull();
        assertThat(PortalLayout.cleanHidden(List.of("plans", "voucher"))).isEqualTo("voucher");
    }

    @Test
    @DisplayName("Hiding the rest works")
    void everythingElseCanBeHidden() {
        assertThat(PortalLayout.hidden("promo,voucher,rewards"))
                .containsExactly("promo", "voucher", "rewards");
    }

    // ------------------------------------------------------------- the knobs

    @Test
    @DisplayName("A knob value we do not know reads as unset, not as an error")
    void unknownKnobValuesFallBackToTheDesign() {
        // Null means "leave the design alone". A value we cannot honour must
        // become null rather than being passed through to the browser, which
        // would put an unknown font name in a CSS variable and silently break
        // the typography.
        assertThat(PortalLayout.oneOf("middle", java.util.Set.of("left", "centre"))).isNull();
        assertThat(PortalLayout.oneOf("", java.util.Set.of("left", "centre"))).isNull();
        assertThat(PortalLayout.oneOf(null, java.util.Set.of("left", "centre"))).isNull();
        assertThat(PortalLayout.oneOf(" CENTRE ", java.util.Set.of("left", "centre")))
                .isEqualTo("centre");
    }

    @Test
    @DisplayName("Corner rounding is clamped rather than refused")
    void radiusIsClamped() {
        // A slider value out of range is our bug or somebody with the API, not an
        // operator decision worth failing a whole save over.
        assertThat(PortalLayout.radius(null)).isNull();
        assertThat(PortalLayout.radius(-5)).isEqualTo(0);
        assertThat(PortalLayout.radius(0)).isEqualTo(0);
        assertThat(PortalLayout.radius(12)).isEqualTo(12);
        assertThat(PortalLayout.radius(999)).isEqualTo(24);
    }

    // -------------------------------------------------------------- storage

    @Test
    @DisplayName("An empty submission stores nothing rather than an empty string")
    void emptySubmissionsStoreNull() {
        // Null is what the rest of this class reads as "unset". Storing "" would
        // read as a saved-but-empty order, and the portal would render no blocks.
        assertThat(PortalLayout.clean(null)).isNull();
        assertThat(PortalLayout.clean(List.of())).isNull();
        assertThat(PortalLayout.clean(List.of("nonsense"))).isNull();
    }

    @Test
    @DisplayName("A partial submission is stored as partial, and filled in on the way out")
    void partialSubmissionsSurvive() {
        // The operator moved what they moved. order() puts the rest after it, so
        // a form that only sends what was dragged is not a form that deletes the
        // rest.
        String stored = PortalLayout.clean(List.of("rewards", "plans"));
        assertThat(stored).isEqualTo("rewards,plans");
        assertThat(PortalLayout.order(stored))
                .containsExactly("rewards", "plans", "promo", "voucher");
    }

    @Test
    @DisplayName("What is stored survives a round trip")
    void roundTrip() {
        PortalSettings saved = PortalSettings.builder()
                .sectionOrder(PortalLayout.clean(List.of("voucher", "promo", "plans", "rewards")))
                .sectionsHidden(PortalLayout.cleanHidden(List.of("rewards", "plans")))
                .contentAlign("centre")
                .cornerRadius(16)
                .logoSize("l")
                .headingFont("serif")
                .density("spacious")
                .build();

        Map<String, Object> described = PortalLayout.describe(saved);

        assertThat(described.get("order"))
                .isEqualTo(List.of("voucher", "promo", "plans", "rewards"));
        assertThat(described.get("hidden")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
                .containsExactly("rewards");
        assertThat(described.get("align")).isEqualTo("centre");
        assertThat(described.get("radius")).isEqualTo(16);
        assertThat(described.get("logoSize")).isEqualTo("l");
        assertThat(described.get("headingFont")).isEqualTo("serif");
        assertThat(described.get("density")).isEqualTo("spacious");
    }
}
