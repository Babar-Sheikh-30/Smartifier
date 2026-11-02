package com.bytesolutions.smartifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@SpringBootApplication
// Populate @CreatedDate fields when MongoDB first persists a document.
@EnableMongoAuditing
public class SmartifierApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartifierApplication.class, args);
    }
}
