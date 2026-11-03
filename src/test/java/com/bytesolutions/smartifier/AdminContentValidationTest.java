package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.controllers.AdminContentController;
import com.bytesolutions.smartifier.controllers.ApiExceptionHandler;
import com.bytesolutions.smartifier.services.AdminContentService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminContentValidationTest {
    @Test void rejectsInvalidContentAndMissingRevisionBeforeCallingService() throws Exception {
        var service = mock(AdminContentService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminContentController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String valid = "\"title\":\"Fact\",\"type\":\"FACT\",\"body\":\"Content\",\"expectedRevision\":0";
        for (String body : new String[] {"{}", "{", "{" + valid.replace("\"Fact\"", "\" \"") + "}",
                "{" + valid.replace("\"FACT\"", "\"UNKNOWN\"") + "}",
                "{" + valid.replace("\"Content\"", "\"" + "x".repeat(1001) + "\"") + "}",
                "{" + valid.replace(",\"expectedRevision\":0", "") + "}"}) {
            mvc.perform(post("/api/admin/stacks/shared/content").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        }
        mvc.perform(post("/api/admin/stacks/shared/remove").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void databaseFailureReturnsSafeRetryableMessage() throws Exception {
        var service = mock(AdminContentService.class);
        when(service.list()).thenThrow(new DataAccessResourceFailureException("Private connection details"));
        var mvc = MockMvcBuilders.standaloneSetup(new AdminContentController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/admin/stacks")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The database is temporarily unavailable. Please try again."));
    }
}
