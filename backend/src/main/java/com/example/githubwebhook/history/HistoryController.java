package com.example.githubwebhook.history;

import com.example.githubwebhook.dispatch.DispatchJob;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HistoryController {

    private final HistoryService service;

    public HistoryController(HistoryService service) {
        this.service = service;
    }

    @GetMapping("/api/history")
    public HistoryPage list(
            @RequestParam(name = "status", required = false) DispatchJob.Status status,
            @RequestParam(name = "before", required = false) Long before,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.list(status, before, limit);
    }
}
