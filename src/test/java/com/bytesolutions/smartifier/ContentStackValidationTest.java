package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.controllers.ContentStackController;
import com.bytesolutions.smartifier.controllers.ApiExceptionHandler;
import com.bytesolutions.smartifier.services.ContentStackService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContentStackValidationTest {
    @Test
    void invalidOrOversizedContentNeverReachesService() throws Exception {
        var service = mock(ContentStackService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ContentStackController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String body : new String[] {
                "{}", "{\"name\":\"Test\",\"items\":[]}",
                "{\"name\":\" \",\"items\":[{\"type\":\"FACT\",\"body\":\"valid\"}]}",
                "{\"name\":\"Test\",\"items\":[null]}",
                "{\"name\":\"Test\",\"items\":[{\"type\":\"UNKNOWN\",\"body\":\"valid\"}]}",
                "{\"name\":\"Test\",\"items\":[{\"type\":null,\"body\":\"valid\"}]}",
                "{\"name\":\"Test\",\"items\":[{\"type\":\"FACT\",\"body\":\"   \"}]}",
                "{\"name\":\"Test\",\"items\":[{\"type\":\"FACT\",\"body\":\"" + "x".repeat(1001) + "\"}]}"}) {
            mvc.perform(post("/api/member/stacks").principal(() -> "member@example.com")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
