package com.example.githubwebhook.history;

import java.util.List;

public record HistoryPage(List<HistoryItem> items, Long nextBefore) {
}
