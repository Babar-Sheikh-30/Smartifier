package com.bytesolutions.smartifier.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.bytesolutions.smartifier.account.Account;
import com.bytesolutions.smartifier.content.ContentStack.Revision;
import com.bytesolutions.smartifier.content.ContentStack.Status;
import com.bytesolutions.smartifier.account.AccountRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Review frozen submissions without editing their content or exposing personal stacks publicly. */
@Service
public class AdminReviewService {
    private final ContentStackRepository stacks;
    private final AccountRepository accounts;

    public AdminReviewService(ContentStackRepository stacks, AccountRepository accounts) {
        this.stacks = stacks;
        this.accounts = accounts;
    }

    public record Entry(String stackId, long revision, String memberName, String memberEmail, Revision version) {}

    public List<Entry> pending() {
        var queue = new ArrayList<Entry>();
        for (var stack : stacks.findPendingReviews()) {
            if (stack.defaultStack() || stack.archived() || stack.ownerId() == null) continue;
            var owner = accounts.findById(stack.ownerId()).filter(this::premiumMember);
            if (owner.isEmpty()) continue;
            for (var version : stack.versions()) {
                if (version.status() == Status.PENDING_REVIEW) {
                    queue.add(new Entry(stack.id(), stack.revision(), owner.get().name(), owner.get().email(), version));
                }
            }
        }
        queue.sort(Comparator.comparing(entry -> entry.version().submittedAt(), Comparator.nullsLast(Comparator.naturalOrder())));
        return queue;
    }

    public void decide(String id, int number, long expectedRevision, boolean approved, String note) {
        if (!approved && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A denial reason is required. Select a reason or write an explanation.");
        }
        var stack = stacks.findById(id)
                .filter(s -> !s.defaultStack() && !s.archived() && s.ownerId() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submitted stack not found."));
        if (accounts.findById(stack.ownerId()).filter(this::premiumMember).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only premium member submissions can be reviewed.");
        }
        if (stack.revision() == null || stack.revision() != expectedRevision) throw conflict();
        var versions = new ArrayList<>(stack.versions());
        int index = stack.versionIndex(number);
        if (index < 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submitted version not found.");
        var submitted = versions.get(index);
        if (submitted.status() != Status.PENDING_REVIEW) throw conflict();
        versions.set(index, submitted.withReview(
                approved ? Status.APPROVED : Status.REJECTED, submitted.submittedAt(),
                note == null || note.isBlank() ? null : note.strip()));
        // A decision updates only this submission. Newer drafts, ownership and publication remain intact.
        try {
            stacks.save(stack.withVersions(versions));
        } catch (OptimisticLockingFailureException exception) { throw conflict(); }
    }

    private boolean premiumMember(Account account) { return account.role() == Account.Role.MEMBER && account.premium(); }
    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "This submission has changed or was already reviewed. Refresh the queue before deciding.");
    }
}
