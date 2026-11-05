-- D-10: without this the 5-second history poll sorts the whole webhook_events table (measured in HistoryQueryPlanIT).
CREATE INDEX idx_webhook_events_received_at ON webhook_events (received_at DESC, id DESC);
