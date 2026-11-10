package com.bytesolutions.smartifier.content;

import java.security.Principal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/member/stacks")
public class ContentStackController {
    private final ContentStackService stacks;
    public ContentStackController(ContentStackService stacks) { this.stacks = stacks; }
    // The client sends the revision it last loaded so stale submissions can be rejected.
    public record Submission(@PositiveOrZero long expectedRevision) {}
    public record Change(@NotNull @PositiveOrZero Long expectedRevision) {}

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

    @PostMapping("/{id}/versions/{number}/cancel-submission")
    public ContentStack cancelSubmission(Principal user, @PathVariable String id, @PathVariable int number,
            @Valid @RequestBody Change change) {
        return stacks.cancelSubmission(user.getName(), id, number, change.expectedRevision());
    }

    @PostMapping("/{id}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Principal user, @PathVariable String id, @Valid @RequestBody Change change) {
        stacks.remove(user.getName(), id, change.expectedRevision());
    }
}
