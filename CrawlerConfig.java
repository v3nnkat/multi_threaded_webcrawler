package com.crawler;

public record CrawlerConfig(
        String seedUrl,
        int maxPages,
        int threads,
        int maxDepth,
        long politeDelayMillis
) {
    public CrawlerConfig(String seedUrl, int maxPages, int threads, int maxDepth) {
        this(seedUrl, maxPages, threads, maxDepth, 500); // default 500ms delay
    }

    public CrawlerConfig {
        if (seedUrl == null || seedUrl.isBlank()) {
            throw new IllegalArgumentException("Seed URL cannot be empty");
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("Max pages must be at least 1");
        }
        if (threads < 1 || threads > 64) {
            throw new IllegalArgumentException(
                    "Threads must be between 1 and 64"
            );
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException(
                    "Max depth cannot be negative"
            );
        }
        if (politeDelayMillis < 0) {
            throw new IllegalArgumentException("Polite delay cannot be negative");
        }
    }
}