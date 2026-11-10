package com.bytesolutions.smartifier.content.services;

import com.bytesolutions.smartifier.content.models.ContentStack;
import com.bytesolutions.smartifier.content.repositories.ContentStackRepository;

import com.bytesolutions.smartifier.account.services.AccountService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.content.models.ContentStack.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Manages published default stacks and versioned personal stacks for premium members. */
@Service
public class ContentStackService {
    private final AccountService accounts;
    private final ContentStackRepository stacks;

    public ContentStackService(AccountService accounts, ContentStackRepository stacks) {
        this.accounts = accounts;
        this.stacks = stacks;
    }

    public record Summary(String id, String name, String description, int itemCount) {}

    public List<Summary> defaults() {
        // Show the approved published version, even when a newer draft exists.
        return stacks.findByDefaultStackTrue().stream()
                .filter(stack -> !stack.archived())
                .filter(stack -> stack.publishedVersion() != null)
                .map(stack -> {
                    var version = stack.versions().stream()
                            .filter(v -> v.number() == stack.publishedVersion() && v.status() == Status.APPROVED)
                            .findFirst().orElseThrow();
                    return new Summary(stack.id(), version.name(), version.description(), version.items().size());
                }).sorted(java.util.Comparator.comparing(Summary::name)).toList();
    }

    private Account premium(String email) {
        // Personal stacks are a member benefit; an admin role alone does not grant access.
        var account = accounts.current(email);
        if (account.role() != Account.Role.MEMBER || !account.premium()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Premium membership is required to manage personal stacks.");
        }
        return account;
    }

    public List<ContentStack> personal(String email) {
        return stacks.findByOwnerId(premium(email).id()).stream()
                .filter(stack -> !stack.archived())
                .sorted(java.util.Comparator.comparing(stack -> stack.latest().createdAt(), java.util.Comparator.reverseOrder()))
                .toList();
    }

    public ContentStack create(String email, Edit edit) {
        var owner = premium(email);
        // A new personal stack has no copy source or published version; MongoDB assigns its revision.
        return stacks.insert(new ContentStack(null, owner.id(), false, null, null, null,
                List.of(draft(1, edit)), null));
    }

    public ContentStack copy(String email, String id) {
        var owner = premium(email);
        var source = stacks.findById(id).filter(ContentStack::defaultStack)
                .filter(stack -> !stack.archived())
                .filter(stack -> stack.publishedVersion() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Default stack not found."));
        var version = source.versions().stream()
                .filter(v -> v.number() == source.publishedVersion() && v.status() == Status.APPROVED)
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Published version not found."));
        var name = version.name();
        // Leave room for the copy suffix within the stack name length limit.
        var edit = new Edit(name.substring(0, Math.min(name.length(), 90)) + " (my copy)",
                version.description(), version.items(), 0);
        return stacks.insert(new ContentStack(null, owner.id(), false, source.id(), version.number(), null,
                List.of(draft(1, edit)), null));
    }

    private ContentStack owned(String email, String id, long expectedRevision) {
        var owner = premium(email);
        var stack = stacks.findByIdAndOwnerId(id, owner.id()).filter(s -> !s.defaultStack() && !s.archived())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Personal stack not found."));
        // Reject edits based on stale client data before attempting to save.
        if (stack.revision() == null || stack.revision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This stack has changed. Reload your stacks before saving.");
        }
        return stack;
    }

    public ContentStack save(String email, String id, Edit edit) {
        var stack = owned(email, id, edit.expectedRevision());
        if (stack.versions().size() >= 40) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This stack has reached its 40-version limit.");
        }
        var versions = new ArrayList<>(stack.versions());
        // Preserve earlier versions so each save retains the stack's history.
        versions.add(draft(stack.latest().number() + 1, edit));
        return persist(stack, versions);
    }

    public ContentStack submit(String email, String id, long expectedRevision) {
        var stack = owned(email, id, expectedRevision);
        if (stack.latest().status() != Status.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a saved draft can be submitted for review.");
        }
        var versions = new ArrayList<>(stack.versions());
        var latest = stack.latest();
        // Submission changes the saved draft's status without creating another content version.
        versions.set(versions.size() - 1, latest.withReview(Status.PENDING_REVIEW, Instant.now(), null));
        return persist(stack, versions);
    }

    public ContentStack cancelSubmission(String email, String id, int number, long expectedRevision) {
        var stack = owned(email, id, expectedRevision);
        var versions = new ArrayList<>(stack.versions());
        int index = stack.versionIndex(number);
        if (index < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submitted version not found.");
        }
        var submitted = versions.get(index);
        if (submitted.status() != Status.PENDING_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request is no longer awaiting approval. Reload your stacks.");
        }
        versions.set(index, submitted.withReview(Status.DRAFT, null, null));
        return persist(stack, versions);
    }

    public void remove(String email, String id, long expectedRevision) {
        var stack = owned(email, id, expectedRevision);
        try {
            // Retain history while excluding the stack from member access and admin review.
            stacks.save(new ContentStack(stack.id(), stack.ownerId(), false, stack.sourceStackId(),
                    stack.sourceVersion(), stack.publishedVersion(), stack.versions(), stack.revision(), true));
        } catch (OptimisticLockingFailureException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This stack has changed. Reload your stacks before removing it.");
        }
        accounts.removeStackSubscription(email, id);
    }

    private Revision draft(int number, Edit edit) {
        return new Revision(number, edit.name().strip(), edit.description() == null ? "" : edit.description().strip(),
                edit.items(), Status.DRAFT, Instant.now(), null, null);
    }

    private ContentStack persist(ContentStack stack, List<Revision> versions) {
        try {
            return stacks.save(stack.withVersions(versions));
        } catch (OptimisticLockingFailureException exception) {
            // Another request may have saved after the initial revision check.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This stack has changed. Reload your stacks before saving.");
        }
    }
}
