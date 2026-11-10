package com.bytesolutions.smartifier.account.validation;

import com.bytesolutions.smartifier.account.services.AccountService;
import com.bytesolutions.smartifier.account.controllers.MemberController;

import com.bytesolutions.smartifier.web.ApiExceptionHandler;
import com.bytesolutions.smartifier.sms.services.SmsPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemberRequestValidationTest {

    @Test
    void subscriptionRequiresNonblankStackAndExplicitBoolean() throws Exception {
        var service = mock(AccountService.class);
        var smsPreferences = mock(SmsPreferenceService.class);

        var mvc = MockMvcBuilders.standaloneSetup(
                new MemberController(service, smsPreferences))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        for (String body : new String[] {
                "{\"subscribed\":true}", "{\"stack\":null,\"subscribed\":true}",
                "{\"stack\":\"\",\"subscribed\":true}", "{\"stack\":\"   \",\"subscribed\":true}",
                "{\"stack\":\"everyday-science\"}", "{\"stack\":\"everyday-science\",\"subscribed\":null}"}) {
            mvc.perform(post("/api/member/subscriptions").principal(() -> "member@example.com")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(service, smsPreferences);
    }

    @Test
    void phoneUpdateRejectsMissingNullEmptyAndWhitespacePhone() throws Exception {
        var service = mock(AccountService.class);
        var smsPreferences = mock(SmsPreferenceService.class);

        var mvc = MockMvcBuilders.standaloneSetup(
                new MemberController(service, smsPreferences))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        for (String body : new String[] {
                "{}", "{\"phoneNumber\":null}", "{\"phoneNumber\":\"\"}", "{\"phoneNumber\":\"   \"}"}) {
            mvc.perform(post("/api/member/phone").principal(() -> "member@example.com")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(service, smsPreferences);
    }
}
