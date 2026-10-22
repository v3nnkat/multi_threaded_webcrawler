package com.crawler;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class WebCrawler {

    private final CrawlerConfig config;
    private final HttpClient httpClient;
    private final RobotsChecker robotsChecker;
    private final Map<String, Long> lastAccessMap = new ConcurrentHashMap<>();
    private static final long MAX_BODY_BYTES = 2 * 1024 * 1024; // 2 MB limit

    public WebCrawler(CrawlerConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.robotsChecker = new RobotsChecker(this.httpClient);
    }

    private record UrlDepth(String url, int depth) {}

    public List<PageResult> crawl() throws InterruptedException {

        String seed = normalizeUrl(config.seedUrl());

        if (seed == null) {
            throw new IllegalArgumentException(
                    "Invalid seed URL: " + config.seedUrl()
            );
        }

        String allowedHost = URI.create(seed).getHost();

        ArrayDeque<UrlDepth> queue = new ArrayDeque<>();
        Set<String> scheduled = new HashSet<>();
        List<PageResult> results = new ArrayList<>();

        ExecutorService pool =
                Executors.newFixedThreadPool(config.threads());

        CompletionService<PageResult> completions =
                new ExecutorCompletionService<>(pool);

        int inFlight = 0;

        // Check robots.txt for seed
        if (!robotsChecker.isAllowed(seed)) {
            throw new IllegalArgumentException("Seed URL is disallowed by robots.txt: " + seed);
        }

        queue.add(new UrlDepth(seed, 0));
        scheduled.add(seed);

        try {
            while (!queue.isEmpty() || inFlight > 0) {

                while (!queue.isEmpty()
                        && inFlight < config.threads()) {

                    UrlDepth task = queue.removeFirst();

                    completions.submit(() ->
                            fetchPage(task.url(), task.depth()));

                    inFlight++;
                }

                if (inFlight > 0) {
                    try {
                        PageResult page =
                                completions.take().get();

                        inFlight--;
                        results.add(page);

                        System.out.printf(
                                "[%d/%d] HTTP %d | depth=%d | %d ms | %s%n",
                                results.size(),
                                config.maxPages(),
                                page.statusCode(),
                                page.depth(),
                                page.elapsedMillis(),
                                page.url()
                        );

                        if (!page.successful()) {
                            System.out.println(
                                    "    Error: " + page.error()
                            );
                        }

                        if (page.successful()
                                && page.depth() < config.maxDepth()) {

                            for (String link : page.links()) {

                                if (scheduled.size()
                                        >= config.maxPages()) {
                                    break;
                                }

                                String normalized =
                                        normalizeUrl(link);

                                if (normalized == null) {
                                    continue;
                                }

                                if (!sameHost(normalized, allowedHost)) {
                                    continue;
                                }

                                // Check robots.txt before scheduling
                                if (!robotsChecker.isAllowed(normalized)) {
                                    continue;
                                }

                                if (scheduled.add(normalized)) {
                                    queue.addLast(
                                            new UrlDepth(
                                                    normalized,
                                                    page.depth() + 1
                                            )
                                    );
                                }
                            }
                        }

                    } catch (ExecutionException e) {
                        inFlight--;
                        System.err.println(
                                "Worker failed: " + e.getCause()
                        );
                    }
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        return results;
    }

    private void enforceRateLimit(String host) throws InterruptedException {
        if (config.politeDelayMillis() <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        Long lastAccess = lastAccessMap.get(host);

        if (lastAccess != null) {
            long elapsed = now - lastAccess;
            if (elapsed < config.politeDelayMillis()) {
                Thread.sleep(config.politeDelayMillis() - elapsed);
            }
        }

        lastAccessMap.put(host, System.currentTimeMillis());
    }

    private PageResult fetchPage(String url, int depth) {
        long start = System.nanoTime();

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null) {
                enforceRateLimit(host);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header(
                            "User-Agent",
                            "LearningCrawler/1.0 (educational project)"
                    )
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            long elapsed = elapsedMillis(start);
            int status = response.statusCode();
            String finalUrl = response.uri().toString();

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("");

            if (status < 200 || status >= 300) {
                response.body().close();
                return new PageResult(
                        finalUrl, "", status, depth, List.of(),
                        elapsed, "HTTP " + status
                );
            }

            if (!contentType.toLowerCase(Locale.ROOT)
                    .contains("text/html")) {
                response.body().close();
                return new PageResult(
                        finalUrl, "", status, depth, List.of(),
                        elapsed, "Not an HTML page"
                );
            }

            byte[] bodyBytes;
            try (InputStream in = response.body()) {
                bodyBytes = in.readNBytes((int) MAX_BODY_BYTES + 1);
            }

            if (bodyBytes.length > MAX_BODY_BYTES) {
                return new PageResult(
                        finalUrl, "", status, depth, List.of(),
                        elapsed, "Response body exceeded size limit"
                );
            }

            String bodyString = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);

            Document document = Jsoup.parse(
                    bodyString, finalUrl
            );

            List<String> links = new ArrayList<>();

            for (Element element : document.select("a[href]")) {
                String absoluteUrl = element.absUrl("href");

                if (!absoluteUrl.isBlank()) {
                    links.add(absoluteUrl);
                }
            }

            return new PageResult(
                    finalUrl,
                    document.title(),
                    status,
                    depth,
                    links,
                    elapsed,
                    null
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PageResult(
                    url, "", 0, depth, List.of(),
                    elapsedMillis(start), "Request interrupted"
            );

        } catch (Exception e) {
            return new PageResult(
                    url, "", 0, depth, List.of(),
                    elapsedMillis(start),
                    e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }

    private static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - start
        );
    }

    private static boolean sameHost(
            String url, String allowedHost) {

        try {
            String host = URI.create(url).getHost();

            return host != null
                    && host.equalsIgnoreCase(allowedHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim()).normalize();
            String scheme = uri.getScheme();

            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))) {
                return null;
            }

            if (uri.getHost() == null
                    || uri.getUserInfo() != null) {
                return null;
            }

            int port = uri.getPort();

            if (port < -1 || port > 65535 || port == 0) {
                return null;
            }

            int effectivePort = port == -1
                    ? (scheme.equalsIgnoreCase("https") ? 443 : 80)
                    : port;

            String authority = uri.getHost().toLowerCase(Locale.ROOT);

            if (!(effectivePort == 80
                    && scheme.equalsIgnoreCase("http"))
                    && !(effectivePort == 443
                    && scheme.equalsIgnoreCase("https"))) {
                authority += ":" + effectivePort;
            }

            String path = uri.getRawPath();

            if (path == null || path.isEmpty()) {
                path = "/";
            }

            String normalized = scheme.toLowerCase(Locale.ROOT)
                    + "://" + authority + path;

            if (uri.getRawQuery() != null) {
                normalized += "?" + uri.getRawQuery();
            }

            return normalized;

        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}