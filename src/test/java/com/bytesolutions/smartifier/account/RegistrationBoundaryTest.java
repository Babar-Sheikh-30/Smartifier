package com.bytesolutions.smartifier.account;

import com.bytesolutions.smartifier.account.AuthController.Registration;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationBoundaryTest {
    private static final String PASSWORD = "Aa1!aaaaaaaa";

    @Test
    void everyRequiredFieldRejectsNullEmptyAndWhitespaceIndependently() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (int field = 0; field < 5; field++) {
                for (String invalid : new String[] {null, "", "   "}) {
                    String[] values = {"Member", "member@example.com", "+14165551234", PASSWORD, PASSWORD};
                    values[field] = invalid;
                    var request = new Registration(values[0], values[1], values[2], values[3], values[4]);
                    String property = new String[] {"name", "email", "phoneNumber", "password", "passwordConfirmation"}[field];
                    assertTrue(validator.validate(request).stream()
                            .anyMatch(v -> v.getPropertyPath().toString().equals(property)), property);
                }
            }
        }
    }

    @Test
    void fieldLengthsAcceptLimitsAndRejectAdjacentValues() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (int length : new int[] {1, 99, 100, 101}) {
                var request = new Registration("a".repeat(length), "member@example.com", "+14165551234", PASSWORD, PASSWORD);
                assertEquals(length <= 100, validator.validate(request).isEmpty(), "name length " + length);
            }
            for (int length : new int[] {253, 254, 255}) {
                // Keep local part and individual domain labels within email syntax limits.
                String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63)
                        + "." + "d".repeat(length - 193);
                var request = new Registration("Member", email, "+14165551234", PASSWORD, PASSWORD);
                assertEquals(length <= 254, validator.validate(request).isEmpty(), "email length " + length);
            }
            for (int length : new int[] {39, 40, 41}) {
                var request = new Registration("Member", "member@example.com", "+" + "1".repeat(length - 1), PASSWORD, PASSWORD);
                assertEquals(length <= 40, validator.validate(request).isEmpty(), "phone input length " + length);
            }
            for (int length : new int[] {11, 12, 13, 63, 64, 65}) {
                String password = "Aa1!" + "a".repeat(length - 4);
                var request = new Registration("Member", "member@example.com", "+14165551234", password, password);
                assertEquals(length >= 12 && length <= 64, validator.validate(request).isEmpty(), "password length " + length);
            }
            for (String email : new String[] {"missing-at.example.com", "@example.com", "member@", "a b@example.com"}) {
                assertFalse(validator.validate(new Registration("Member", email, "+14165551234", PASSWORD, PASSWORD)).isEmpty(), email);
            }
        }
    }
}
