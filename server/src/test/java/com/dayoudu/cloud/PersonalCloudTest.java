package com.dayoudu.cloud;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PersonalCloudTest {
    @Test void disabledRegistrationRejectsBeforeAnyDatabaseWrite() {
        var auth = new Authentication(null, null);
        auth.registrationEnabled = false;
        var failure = assertThrows(ApiError.Failure.class,
            () -> auth.register("new account", "Example12!", "203.0.113.10"));
        assertEquals(403, failure.status);
        assertTrue(failure.getMessage().contains("未开放"));
    }
}
