package com.bytesolutions.smartifier.content.validation;

import com.bytesolutions.smartifier.content.controllers.AdminReviewController;
import com.bytesolutions.smartifier.content.services.AdminReviewService;

import com.bytesolutions.smartifier.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminReviewValidationTest {
    @Test void invalidDecisionMissingRevisionAndOverlongNotesNeverReachTheService() throws Exception {
        var service = mock(AdminReviewService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminReviewController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String body : new String[] {"{}", "{", "{\"expectedRevision\":0}", "{\"approved\":true}",
                "{\"expectedRevision\":-1,\"approved\":true}",
                "{\"expectedRevision\":0,\"approved\":false,\"note\":\"" + "x".repeat(501) + "\"}"}) {
            mvc.perform(post("/api/admin/reviews/personal/versions/1/decision").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(
                            "Choose approve or deny, include the current revision, and keep the review note within 500 characters."));
        }
        verifyNoInteractions(service);
    }
}
