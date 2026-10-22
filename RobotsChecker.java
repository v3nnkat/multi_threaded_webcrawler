package com.crawler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RobotsChecker {

    private final HttpClient httpClient;
    private final Map<String, List<String>> disallowCache = new ConcurrentHashMap<>();
    private static final String USER_AGENT = "LearningCrawler";

    public RobotsChecker(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public boolean isAllowed(String urlStr) {
        try {
            URI uri = URI.create(urlStr);
            String host = uri.getHost();
            if (host == null) {
                return true;
            }

            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            String baseHostKey = scheme + "://" + authority;

            List<String> disallows = disallowCache.computeIfAbsent(baseHostKey, k -> fetchDisallows(baseHostKey));

            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = uri.getQuery();
            if (query != null) {
                path += "?" + query;
            }

            for (String disallow : disallows) {
                if (!disallow.isEmpty() && path.startsWith(disallow)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // If robots.txt cannot be checked, default to allowed but proceed cautiously
            return true;
        }
    }

    private List<String> fetchDisallows(String baseHostKey) {
        List<String> disallows = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseHostKey + "/robots.txt"))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", USER_AGENT + "/1.0 (educational project)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                String[] lines = response.body().split("\n");
                boolean relevantUserAgent = false;

                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    int idx = line.indexOf(':');
                    if (idx == -1) continue;

                    String key = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(idx + 1).trim();

                    if (key.equals("user-agent")) {
                        relevantUserAgent = value.equals("*") || value.toLowerCase(Locale.ROOT).contains(USER_AGENT.toLowerCase(Locale.ROOT));
                    } else if (key.equals("disallow") && relevantUserAgent) {
                        disallows.add(value);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore failure to fetch robots.txt (e.g., 404 Not Found means everything is allowed)
        }
        return disallows;
    }
}