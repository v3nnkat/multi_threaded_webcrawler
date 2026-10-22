package com.crawler;

import java.util.List;
import java.util.Objects;

public record PageResult(
        String url,
        String title,
        int statusCode,
        int depth,
        List<String> links,
        long elapsedMillis,
        String error
) {
    public PageResult {
        url = Objects.requireNonNull(url, "URL cannot be null");
        title = title == null ? "" : title;
        links = List.copyOf(links);
    }

    public boolean successful() {
        return error == null
                && statusCode >= 200
                && statusCode < 300;
    }
}