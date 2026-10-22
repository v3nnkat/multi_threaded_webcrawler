package com.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ResultStorage {

    private static final String HEADER =
            "url,title,statusCode,depth,links,elapsedMillis,error";

    public static void save(
            List<PageResult> results, Path file) throws IOException {

        Path parent = file.toAbsolutePath().getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<String> lines = new ArrayList<>();
        lines.add(HEADER);

        for (PageResult page : results) {
            lines.add(csvRow(List.of(
                    safe(page.url()),
                    safe(page.title()),
                    String.valueOf(page.statusCode()),
                    String.valueOf(page.depth()),
                    String.join("\n", page.links()),
                    String.valueOf(page.elapsedMillis()),
                    safe(page.error())
            )));
        }

        Files.write(file, lines, StandardCharsets.UTF_8);
        System.out.println("Saved " + results.size()
                + " results to " + file);
    }

    public static List<PageResult> load(Path file)
            throws IOException {

        String content =
                Files.readString(file, StandardCharsets.UTF_8);

        List<List<String>> rows = parseCsv(content);
        List<PageResult> results = new ArrayList<>();

        if (rows.isEmpty()) {
            return results;
        }

        if (!rows.get(0).equals(List.of(
                "url", "title", "statusCode", "depth",
                "links", "elapsedMillis", "error"))) {
            throw new IOException("Unrecognized crawler CSV format");
        }

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);

            if (row.size() != 7) {
                throw new IOException(
                        "Invalid CSV row at record " + (i + 1)
                );
            }

            List<String> links = row.get(4).isEmpty()
                    ? List.of()
                    : List.of(row.get(4).split("\n", -1));

            results.add(new PageResult(
                    row.get(0),
                    row.get(1),
                    Integer.parseInt(row.get(2)),
                    Integer.parseInt(row.get(3)),
                    links,
                    Long.parseLong(row.get(5)),
                    row.get(6).isEmpty() ? null : row.get(6)
            ));
        }

        return results;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String csvRow(List<String> fields) {
        return fields.stream()
                .map(ResultStorage::escape)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String escape(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static List<List<String>> parseCsv(String content)
            throws IOException {

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length()
                            && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.length() == 0) {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                row.add(field.toString());
                field.setLength(0);

                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }

                row = new ArrayList<>();

                if (c == '\r' && i + 1 < content.length()
                        && content.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                field.append(c);
            }
        }

        if (quoted) {
            throw new IOException("CSV contains an unclosed quoted field");
        }

        if (!row.isEmpty() || field.length() > 0) {
            row.add(field.toString());
            rows.add(row);
        }

        return rows;
    }
}