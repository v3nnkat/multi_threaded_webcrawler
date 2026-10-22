
package com.crawler;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class ResultFilter {

    public static List<PageResult> filter(
            List<PageResult> results,
            String search,
            Integer status,
            Boolean successful,
            Integer depth) {

        String keyword = search == null
                ? ""
                : search.toLowerCase(Locale.ROOT);

        return results.stream()
                .filter(page ->
                        keyword.isEmpty()
                        || page.url().toLowerCase(Locale.ROOT)
                                .contains(keyword)
                        || page.title().toLowerCase(Locale.ROOT)
                                .contains(keyword))
                .filter(page ->
                        status == null
                        || page.statusCode() == status)
                .filter(page ->
                        successful == null
                        || page.successful() == successful)
                .filter(page ->
                        depth == null
                        || page.depth() == depth)
                .collect(Collectors.toList());
    }
}