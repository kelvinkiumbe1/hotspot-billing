package com.spalimited.hotspotbilling.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * A phone number this operator could actually dial.
 *
 * <p>Replaces the {@code @Pattern(regexp = "254\d{9}")} that was copied into
 * nine controllers. That pattern is why a Ghanaian ISP could set their country,
 * currency and language correctly and still not take a single payment: every
 * 233 number was refused before it reached a gateway.
 *
 * <p>Deliberately permissive about how the customer types it — with a plus,
 * with a leading zero, with spaces — because the shape they use is not their
 * mistake to pay for.
 */
@Documented
@Constraint(validatedBy = PhoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Phone {

    String message() default "That doesn't look like a phone number we can reach";

    /** Whether a blank value passes. Off by default; @NotBlank says that better. */
    boolean optional() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
