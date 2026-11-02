package com.bytesolutions.smartifier.bootstrap;

import java.time.Instant;
import java.util.List;
import com.bytesolutions.smartifier.models.ContentStack;
import com.bytesolutions.smartifier.models.ContentStack.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class ContentStackBootstrap implements ApplicationRunner {
    private final MongoTemplate mongo;
    public ContentStackBootstrap(MongoTemplate mongo) { this.mongo = mongo; }

    @Override
    public void run(ApplicationArguments arguments) {
        seed("everyday-science", "Everyday science", "A daily mix to feed your curiosity.", List.of(
                new Item(Type.FACT, "Octopuses have three hearts.", "", ""),
                new Item(Type.JOKE, "Why did the bicycle fall over?", "It was two-tired.", "")));
        seed("words-and-language", "Words and language", "Words, wit, and fresh perspectives.", List.of(
                new Item(Type.VOCABULARY, "Serendipity", "Finding something valuable or pleasant by chance.", ""),
                new Item(Type.FACT, "A palindrome reads the same forwards and backwards, such as level.", "", "")));
        seed("learning-habits", "Learning habits", "Small ideas for a curious mind.", List.of(
                new Item(Type.QUOTE, "Well done is better than well said.", "", "Benjamin Franklin"),
                new Item(Type.FACT, "Spacing practice over several days can help you remember what you learn.", "", "")));
    }

    private void seed(String id, String name, String description, List<Item> items) {
        // Insert only: restarting never overwrites a default's content or version history.
        mongo.upsert(Query.query(Criteria.where("id").is(id)), new Update()
                .setOnInsert("defaultStack", true).setOnInsert("ownerId", null)
                .setOnInsert("publishedVersion", 1).setOnInsert("revision", 0L)
                .setOnInsert("versions", List.of(new Revision(1, name, description, items,
                        Status.APPROVED, Instant.now(), null, null))), ContentStack.class);
    }
}
