package com.bytesolutions.smartifier.content.validation;

import com.bytesolutions.smartifier.content.controllers.ContentStackController;
import com.bytesolutions.smartifier.content.services.ContentStackService;

import com.bytesolutions.smartifier.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContentStackValidationTest {
    @Test
    void nameAndDescriptionAreRequiredForCreatingAndSaving() throws Exception {
        var service = mock(ContentStackService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ContentStackController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String field : new String[] {"name", "description"}) {
            for (String value : new String[] {null, "", " \n\t "}) {
                var body = new java.util.HashMap<String, Object>();
                body.put("name", "My stack");
                body.put("description", "Interesting facts");
                body.put("items", java.util.List.of(java.util.Map.of("type", "FACT", "body", "A fact")));
                body.put("expectedRevision", 0);
                if (value == null) body.remove(field);
                else body.put(field, value);
                for (String path : new String[] {"/api/member/stacks", "/api/member/stacks/personal/versions"}) {
                    mvc.perform(post(path).principal(() -> "member@example.com")
                            .contentType(MediaType.APPLICATION_JSON).content(new org.bson.Document(body).toJson()))
                            .andExpect(status().isBadRequest())
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                                            field.equals("name") ? "Stack name is required" : "Description is required")));
                }
            }
        }
        verifyNoInteractions(service);
    }

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
                    .contentType(MediaType.APPLICATION_JSON).content(new org.bson.Document(org.bson.Document.parse(body))
                            .append("description", "A valid description").toJson())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
