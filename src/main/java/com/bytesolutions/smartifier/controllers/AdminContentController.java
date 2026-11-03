package com.bytesolutions.smartifier.controllers;

import java.util.List;
import com.bytesolutions.smartifier.models.ContentStack;
import com.bytesolutions.smartifier.models.ContentStack.*;
import com.bytesolutions.smartifier.services.AdminContentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/stacks")
public class AdminContentController {
    private final AdminContentService service;
    public AdminContentController(AdminContentService service) { this.service = service; }

    public record Metadata(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {}
    public record MetadataEdit(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
            @NotNull @PositiveOrZero Long expectedRevision) {}
    public record Change(@NotNull @PositiveOrZero Long expectedRevision) {}
    public record ContentEdit(@NotNull Type type, @NotBlank @Size(max = 1000) String body,
            @Size(max = 1000) String answer, @Size(max = 300) String attribution,
            @NotNull @PositiveOrZero Long expectedRevision) {
        Item item() { return new Item(type, body, answer, attribution); }
    }

    @GetMapping public List<ContentStack> list() { return service.list(); }
    @GetMapping("/{id}") public ContentStack get(@PathVariable String id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ContentStack create(@Valid @RequestBody Metadata body) { return service.create(body.name(), body.description()); }
    @PostMapping("/{id}/versions")
    public ContentStack metadata(@PathVariable String id, @Valid @RequestBody MetadataEdit body) {
        return service.metadata(id, body.expectedRevision(), body.name(), body.description());
    }
    @PostMapping("/{id}/publish")
    public ContentStack publishMetadata(@PathVariable String id, @Valid @RequestBody Change body) {
        return service.publishMetadata(id, body.expectedRevision());
    }
    @PostMapping("/{id}/remove") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id, @Valid @RequestBody Change body) { service.remove(id, body.expectedRevision()); }
    @PostMapping("/{id}/content") @ResponseStatus(HttpStatus.CREATED)
    public ContentStack add(@PathVariable String id, @Valid @RequestBody ContentEdit body) {
        return service.add(id, body.expectedRevision(), body.item());
    }
    @PostMapping("/{id}/content/{itemId}/versions")
    public ContentStack edit(@PathVariable String id, @PathVariable String itemId, @Valid @RequestBody ContentEdit body) {
        return service.edit(id, itemId, body.expectedRevision(), body.item());
    }
    @PostMapping("/{id}/content/{itemId}/publish")
    public ContentStack publish(@PathVariable String id, @PathVariable String itemId, @Valid @RequestBody Change body) {
        return service.publish(id, itemId, body.expectedRevision());
    }
    @PostMapping("/{id}/content/{itemId}/remove")
    public ContentStack removeItem(@PathVariable String id, @PathVariable String itemId, @Valid @RequestBody Change body) {
        return service.removeItem(id, itemId, body.expectedRevision());
    }
}
