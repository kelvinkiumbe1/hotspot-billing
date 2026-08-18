package com.spalimited.hotspotbilling.config;

import com.spalimited.hotspotbilling.service.i18n.PhoneNumbers;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

/**
 * Validates against the operator's own country rather than a baked-in one.
 *
 * <p>The message names the shape expected <em>here</em>, because "must be in
 * 2547XXXXXXXX format" told a Ghanaian customer to type a number that is not
 * theirs — and a validation message that asks for the impossible is worse than
 * no message.
 */
@RequiredArgsConstructor
public class PhoneValidator implements ConstraintValidator<Phone, String> {

    private final PhoneNumbers phones;

    private boolean optional;

    @Override
    public void initialize(Phone annotation) {
        this.optional = annotation.optional();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return optional;
        }
        if (phones.isValid(value)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                "Enter a phone number like " + phones.example()).addConstraintViolation();
        return false;
    }
}
