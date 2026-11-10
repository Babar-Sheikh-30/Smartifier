package com.bytesolutions.smartifier.content.services;

import com.bytesolutions.smartifier.content.models.ContentStack;
import com.bytesolutions.smartifier.content.repositories.ContentStackRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.bytesolutions.smartifier.content.models.ContentStack.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Shared-stack administration; SecurityConfig restricts its controller to administrators. */
@Service
public class AdminContentService {
    private final ContentStackRepository stacks;
    public AdminContentService(ContentStackRepository stacks) { this.stacks = stacks; }

    public List<ContentStack> list() {
        return stacks.findByDefaultStackTrue().stream().filter(s -> !s.archived() && s.ownerId() == null)
                .map(this::identified).sorted(Comparator.comparing(s -> s.latest().name())).toList();
    }

    public ContentStack get(String id) {
        return identified(stacks.findById(id).filter(s -> s.defaultStack() && s.ownerId() == null && !s.archived())
                .orElseThrow(() -> missing("Stack not found. Reload the stack list.")));
    }

    public ContentStack create(String name, String description) {
        return stacks.insert(new ContentStack(null, null, true, null, null, null,
                List.of(version(1, name.strip(), text(description), List.of(), Status.DRAFT)), null));
    }

    public ContentStack metadata(String id, long expected, String name, String description) {
        var stack = current(id, expected);
        return append(stack, name.strip(), text(description), stack.latest().items(), null, false);
    }

    public ContentStack add(String id, long expected, Item input) {
        var stack = current(id, expected);
        if (stack.latest().items().size() >= 50) throw conflict("A stack can contain at most 50 items.");
        var items = new ArrayList<>(stack.latest().items());
        items.add(clean(input, UUID.randomUUID().toString()));
        return append(stack, stack.latest().name(), stack.latest().description(), items, null, false);
    }

    public ContentStack edit(String id, String itemId, long expected, Item input) {
        var stack = current(id, expected);
        item(stack.latest().items(), itemId);
        var items = stack.latest().items().stream().map(i -> i.id().equals(itemId) ? clean(input, itemId) : i).toList();
        return append(stack, stack.latest().name(), stack.latest().description(), items, null, false);
    }

    public ContentStack publish(String id, String itemId, long expected) {
        var stack = current(id, expected);
        var selected = item(stack.latest().items(), itemId);
        var live = new ArrayList<>(published(stack));
        live.removeIf(i -> i.id().equals(itemId));
        live.add(selected);
        // Build the live snapshot from previously published items, never other drafts.
        return append(stack, stack.latest().name(), stack.latest().description(), stack.latest().items(), live, true);
    }

    public ContentStack publishMetadata(String id, long expected) {
        var stack = current(id, expected);
        if (published(stack).isEmpty()) throw conflict("Publish at least one content item first.");
        return append(stack, stack.latest().name(), stack.latest().description(), stack.latest().items(), published(stack), true);
    }

    public ContentStack removeItem(String id, String itemId, long expected) {
        var stack = current(id, expected);
        item(stack.latest().items(), itemId);
        var working = stack.latest().items().stream().filter(i -> !i.id().equals(itemId)).toList();
        var live = published(stack);
        boolean wasPublished = live.stream().anyMatch(i -> i.id().equals(itemId));
        return append(stack, stack.latest().name(), stack.latest().description(), working,
                live.stream().filter(i -> !i.id().equals(itemId)).toList(), wasPublished, false);
    }

    public void remove(String id, long expected) {
        var stack = current(id, expected);
        save(new ContentStack(stack.id(), null, true, null, null, stack.publishedVersion(),
                stack.versions(), stack.revision(), true));
    }

    private ContentStack current(String id, long expected) {
        var stack = get(id);
        if (stack.revision() == null || stack.revision() != expected) throw conflict("This stack has changed. Reload before saving.");
        return stack;
    }

    private List<Item> published(ContentStack stack) {
        if (stack.publishedVersion() == null) return List.of();
        return stack.versions().stream().filter(v -> v.number() == stack.publishedVersion() && v.status() == Status.APPROVED)
                .findFirst().orElseThrow(() -> conflict("Published stack version is unavailable.")).items();
    }

    private ContentStack append(ContentStack stack, String name, String description, List<Item> working,
            List<Item> live, boolean changePublication) {
        return append(stack, name, description, working, live, changePublication, true);
    }

    private ContentStack append(ContentStack stack, String name, String description, List<Item> working,
            List<Item> live, boolean changePublication, boolean publishDetails) {
        var versions = new ArrayList<>(stack.versions());
        int additions = changePublication && !live.isEmpty() ? 2 : 1;
        if (versions.size() + additions > 40) throw conflict("This stack has reached its 40-version limit.");
        int number = stack.latest().number() + 1;
        Integer publishedVersion = stack.publishedVersion();
        if (changePublication) {
            publishedVersion = null;
            if (!live.isEmpty()) {
                publishedVersion = number;
                var previous = stack.versions().stream().filter(v -> Objects.equals(v.number(), stack.publishedVersion())).findFirst();
                String liveName = !publishDetails && previous.isPresent() ? previous.get().name() : name;
                String liveDescription = !publishDetails && previous.isPresent() ? previous.get().description() : description;
                versions.add(version(number++, liveName, liveDescription, live, Status.APPROVED));
            }
        }
        // Keep the complete working set last, even after publishing a single item.
        versions.add(version(number, name, description, working, Status.DRAFT));
        return save(new ContentStack(stack.id(), null, true, null, null, publishedVersion, versions, stack.revision()));
    }

    private ContentStack save(ContentStack stack) {
        try { return stacks.save(stack); }
        catch (OptimisticLockingFailureException exception) { throw conflict("This stack has changed. Reload before saving."); }
    }

    private Revision version(int number, String name, String description, List<Item> items, Status status) {
        return new Revision(number, name, description, items, status, Instant.now(), null, null);
    }

    private Item item(List<Item> items, String id) {
        return items.stream().filter(i -> id.equals(i.id())).findFirst()
                .orElseThrow(() -> missing("Content no longer exists. Reload the stack."));
    }

    private Item clean(Item item, String id) {
        return new Item(item.type(), item.body().strip(), text(item.answer()), text(item.attribution()),
                id, null, null);
    }

    private String text(String value) { return value == null ? "" : value.strip(); }

    // Legacy content has no IDs. Deterministic IDs are stable across reads and matching
    // snapshots; they are persisted only when an administrator saves this document.
    private ContentStack identified(ContentStack stack) {
        var versions = stack.versions().stream().map(v -> {
            Map<String, Integer> occurrences = new HashMap<>();
            var items = v.items().stream().map(i -> {
                if (i.id() != null) return i;
                String key = i.type() + "\u0000" + i.body() + "\u0000" + text(i.answer()) + "\u0000" + text(i.attribution());
                int occurrence = occurrences.merge(key, 1, Integer::sum);
                String id = UUID.nameUUIDFromBytes((stack.id() + key + occurrence).getBytes(StandardCharsets.UTF_8)).toString();
                return new Item(i.type(), i.body(), i.answer(), i.attribution(), id, i.title(), i.description());
            }).toList();
            return new Revision(v.number(), v.name(), v.description(), items, v.status(), v.createdAt(), v.submittedAt(), v.reviewNote());
        }).toList();
        return new ContentStack(stack.id(), stack.ownerId(), stack.defaultStack(), stack.sourceStackId(), stack.sourceVersion(),
                stack.publishedVersion(), versions, stack.revision(), stack.archived());
    }

    private ResponseStatusException missing(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
