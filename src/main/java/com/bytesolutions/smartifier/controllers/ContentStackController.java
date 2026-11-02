package com.bytesolutions.smartifier.controllers;

import java.security.Principal;
import java.util.List;
import com.bytesolutions.smartifier.models.ContentStack;
import com.bytesolutions.smartifier.services.ContentStackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/member/stacks")
public class ContentStackController {
    private final ContentStackService stacks;
    public ContentStackController(ContentStackService stacks) { this.stacks = stacks; }
    public record Submission(@PositiveOrZero long expectedRevision) {}

    @GetMapping("/defaults")
    public List<ContentStackService.Summary> defaults() { return stacks.defaults(); }

    @GetMapping
    public List<ContentStack> personal(Principal user) { return stacks.personal(user.getName()); }

    @PostMapping
    public ContentStack create(Principal user, @Valid @RequestBody ContentStack.Edit edit) {
        return stacks.create(user.getName(), edit);
    }

    @PostMapping("/{id}/copy")
    public ContentStack copy(Principal user, @PathVariable String id) { return stacks.copy(user.getName(), id); }

    @PostMapping("/{id}/versions")
    public ContentStack save(Principal user, @PathVariable String id, @Valid @RequestBody ContentStack.Edit edit) {
        return stacks.save(user.getName(), id, edit);
    }

    @PostMapping("/{id}/submit")
    public ContentStack submit(Principal user, @PathVariable String id, @Valid @RequestBody Submission submission) {
        return stacks.submit(user.getName(), id, submission.expectedRevision());
    }
}
