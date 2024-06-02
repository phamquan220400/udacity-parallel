package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
    private final Clock clock;
    private final Duration timeout;
    private final int popularWordCount;
    private final ForkJoinPool pool;
    private final List<Pattern> ignoredUrlsList;
    private final int maxDepth;
    private final PageParserFactory parserFactory;

    @Inject
    ParallelWebCrawler(
            Clock clock,
            @Timeout Duration timeout,
            @PopularWordCount int popularWordCount,
            @TargetParallelism int threadCount,
            @IgnoredUrls List<Pattern> ignoredUrlsList,
            @MaxDepth int maxDepth,
            PageParserFactory parserFactory) {
        this.clock = clock;
        this.timeout = timeout;
        this.popularWordCount = popularWordCount;
        this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
        this.ignoredUrlsList = ignoredUrlsList;
        this.maxDepth = maxDepth;
        this.parserFactory = parserFactory;
    }

    @Override
    public CrawlResult crawl(List<String> startingUrls) {
        Instant deadline = clock.instant().plus(timeout);
        ConcurrentMap<String, Integer> counts = new ConcurrentHashMap<>();
        ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();
        for (String url : startingUrls) {
            pool.invoke(new InternalCrawler(url, deadline, maxDepth, counts, visitedUrls));
        }
        int visitedUrlsSize = visitedUrls.size();
        Map<String, Integer> wordCounts = counts.isEmpty() ? counts : WordCounts.sort(counts, popularWordCount);
        return new CrawlResult.Builder()
                .setWordCounts(wordCounts)
                .setUrlsVisited(visitedUrlsSize)
                .build();
    }

    @Override
    public int getMaxParallelism() {
        return Runtime.getRuntime().availableProcessors();
    }

    public class InternalCrawler extends RecursiveTask<Boolean> {
        private final String url;
        private final Instant timeOut;
        private final int maxDepth;
        private final ConcurrentMap<String, Integer> counts;
        private final ConcurrentSkipListSet<String> visitedUrls;

        public InternalCrawler(
                String url,
                Instant timeOut,
                int maxDepth,
                ConcurrentMap<String, Integer> counts,
                ConcurrentSkipListSet<String> visitedUrls
        ) {
            this.url = url;
            this.timeOut = timeOut;
            this.maxDepth = maxDepth;
            this.counts = counts;
            this.visitedUrls = visitedUrls;
        }

        @Override
        protected Boolean compute() {
            if (maxDepth == 0 || clock.instant().isAfter(timeOut)) {
                return false;
            }
            for (Pattern pattern : ignoredUrlsList) {
                if (pattern.matcher(url).matches()) {
                    return false;
                }
            }
            if (!visitedUrls.add(url)) {
                return false;
            }
            PageParser.Result result = parserFactory.get(url).parse();

            result.getWordCounts().forEach((key, value) ->
                    counts.merge(key, value, Integer::sum)
            );

            List<InternalCrawler> subtasks = result.getLinks().stream()
                    .map(link -> new InternalCrawler(link, timeOut, maxDepth - 1, counts, visitedUrls))
                    .collect(Collectors.toList());

            invokeAll(subtasks);

            return true;
        }
    }
}
