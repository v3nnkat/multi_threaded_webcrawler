package com.crawler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class Main {

    private record Options(
            String search,
            Integer status,
            Boolean successful,
            Integer depth,
            Path saveDir,
            Path output
    ) {}

    public static void main(String[] args) {

        if (args.length == 1 && args[0].equals("--help")) {
            printUsage();
            return;
        }

        try {
            boolean loadMode = args.length > 0
                    && args[0].equals("--load");

            if (loadMode) {
                if (args.length < 2) {
                    throw new IllegalArgumentException(
                            "--load requires a CSV file path"
                    );
                }

                Path input = Path.of(args[1]);
                Options options = parseOptions(args, 2);

                List<PageResult> all = ResultStorage.load(input);

                System.out.println("Loaded " + all.size()
                        + " saved results from " + input);

                List<PageResult> filtered = filter(all, options);

                printResults(filtered);

                Path saveDir = options.saveDir() != null
                        ? options.saveDir()
                        : Path.of("results");

                Path output = options.output() != null
                        ? options.output()
                        : saveDir.resolve("filtered-results.csv");

                if (input.toAbsolutePath().equals(output.toAbsolutePath())) {
                    throw new IllegalArgumentException(
                            "Output path cannot be the same as the input file in --load mode (prevents data overwrite)."
                    );
                }

                ResultStorage.save(filtered, output);

            } else {
                if (args.length < 4) {
                    printUsage();
                    System.exit(1);
                    return;
                }

                String seedUrl = args[0];
                int maxPages = Integer.parseInt(args[1]);
                int threads = Integer.parseInt(args[2]);
                int maxDepth = Integer.parseInt(args[3]);

                Options options = parseOptions(args, 4);

                CrawlerConfig config = new CrawlerConfig(
                        seedUrl, maxPages, threads, maxDepth
                );

                WebCrawler crawler = new WebCrawler(config);

                long start = System.nanoTime();
                List<PageResult> all = crawler.crawl();
                long elapsed = (System.nanoTime() - start)
                        / 1_000_000;

                Path saveDir = options.saveDir() != null
                        ? options.saveDir()
                        : Path.of("results");

                ResultStorage.save(
                        all, saveDir.resolve("all-results.csv")
                );

                List<PageResult> filtered = filter(all, options);

                printResults(filtered);

                Path output = options.output() != null
                        ? options.output()
                        : saveDir.resolve("filtered-results.csv");

                ResultStorage.save(filtered, output);

                long successfulCount = all.stream()
                        .filter(PageResult::successful)
                        .count();

                System.out.println("\n========== CRAWL SUMMARY ==========");
                System.out.println("Pages crawled: " + all.size());
                System.out.println("Successful:    " + successfulCount);
                System.out.println("Failed:        "
                        + (all.size() - successfulCount));
                System.out.println("Matched:       " + filtered.size());
                System.out.println("Elapsed:       " + elapsed + " ms");
                System.out.println("===================================");
            }

        } catch (NumberFormatException e) {
            System.err.println("Error: numeric arguments must be integers.");
            System.exit(1);

        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Crawl interrupted.");
            System.exit(1);
        }
    }

    private static Options parseOptions(String[] args, int start) {
        String search = null;
        Integer status = null;
        Boolean successful = null;
        Integer depth = null;
        Path saveDir = null;
        Path output = null;

        for (int i = start; i < args.length; i++) {
            switch (args[i]) {
                case "--search" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException(
                                "--search requires a keyword");
                    }
                    search = args[i];
                }
                case "--status" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException(
                                "--status requires a code");
                    }
                    status = Integer.parseInt(args[i]);
                }
                case "--success" -> {
                    if (Boolean.FALSE.equals(successful)) {
                        throw new IllegalArgumentException(
                                "Choose either --success or --failed");
                    }
                    successful = true;
                }
                case "--failed" -> {
                    if (Boolean.TRUE.equals(successful)) {
                        throw new IllegalArgumentException(
                                "Choose either --success or --failed");
                    }
                    successful = false;
                }
                case "--depth" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException(
                                "--depth requires a number");
                    }
                    depth = Integer.parseInt(args[i]);
                }
                case "--save-dir" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException(
                                "--save-dir requires a directory");
                    }
                    saveDir = Path.of(args[i]);
                }
                case "--output" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException(
                                "--output requires a file path");
                    }
                    output = Path.of(args[i]);
                }
                default -> throw new IllegalArgumentException(
                        "Unknown option: " + args[i]);
            }
        }

        if (status != null && (status < 100 || status > 599)) {
            throw new IllegalArgumentException(
                    "HTTP status must be between 100 and 599");
        }

        if (depth != null && depth < 0) {
            throw new IllegalArgumentException(
                    "Filter depth cannot be negative");
        }

        if (Boolean.TRUE.equals(successful) && status != null
                && (status < 200 || status >= 300)) {
            throw new IllegalArgumentException(
                    "--success conflicts with this HTTP status");
        }

        if (Boolean.FALSE.equals(successful) && status != null
                && status >= 200 && status < 300) {
            throw new IllegalArgumentException(
                    "--failed conflicts with this HTTP status");
        }

        return new Options(
                search, status, successful, depth, saveDir, output
        );
    }

    private static List<PageResult> filter(
            List<PageResult> all, Options options) {

        return ResultFilter.filter(
                all,
                options.search(),
                options.status(),
                options.successful(),
                options.depth()
        );
    }

    private static void printResults(List<PageResult> results) {
        System.out.println("\n========== MATCHING RESULTS ==========");

        if (results.isEmpty()) {
            System.out.println("No results matched your filters.");
        }

        for (PageResult page : results) {
            System.out.printf("HTTP %d | depth=%d | %d ms%n",
                    page.statusCode(),
                    page.depth(),
                    page.elapsedMillis());

            System.out.println("URL:   " + page.url());

            if (!page.title().isBlank()) {
                System.out.println("Title: " + page.title());
            }

            if (page.error() != null) {
                System.out.println("Error: " + page.error());
            }

            System.out.println();
        }

        System.out.println("Matching results: " + results.size());
    }

    private static void printUsage() {
        System.out.println("""
                Multithreaded Web Crawler

                Crawl:
                  mvn exec:java -Dexec.mainClass="com.crawler.Main" \\
                    -Dexec.args="https://example.com 50 4 2 --search about"

                Options:
                  --search <keyword>   Search URL or title
                  --status <code>      Filter by HTTP status
                  --success            Successful pages only
                  --failed             Failed pages only
                  --depth <number>     Match exact crawl depth
                  --save-dir <dir>     Directory for crawl CSV files
                  --output <file>      Custom filtered-results file

                Search saved results without crawling:
                  mvn exec:java -Dexec.mainClass="com.crawler.Main" \\
                    -Dexec.args="--load results/all-results.csv --search about"

                Saved files:
                  results/all-results.csv
                  results/filtered-results.csv
                """);
    }
}