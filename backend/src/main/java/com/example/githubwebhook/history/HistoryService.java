package com.example.githubwebhook.history;

import com.example.githubwebhook.dispatch.DispatchJob;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class HistoryService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final HistoryQueryRepository repository;

    public HistoryService(HistoryQueryRepository repository) {
        this.repository = repository;
    }

    public HistoryPage list(DispatchJob.Status status, Long before, Integer limit) {
        // D-08: any number is corrected, never rejected.
        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        Set<DispatchJob.Status> statuses = status == null ? EnumSet.allOf(DispatchJob.Status.class) : EnumSet.of(status);
        // limit+1: the extra row only tells us whether another page exists.
        PageRequest window = PageRequest.ofSize(n + 1);
        List<HistoryRow> rows = before == null
                ? repository.firstPage(statuses, window)
                : repository.pageBefore(statuses, before, window);
        List<HistoryItem> items = rows.stream().limit(n).map(HistoryItem::from).toList();
        Long next = rows.size() > n ? items.get(items.size() - 1).eventId() : null;
        return new HistoryPage(items, next);
    }
}
