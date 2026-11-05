-- D-01 (5 job states), D-03 (no raw payload), D-04 (discord_message_id). Never edit after commit; add V2+.
CREATE TABLE webhook_events (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  delivery_id  text        NOT NULL UNIQUE,
  event_type   text        NOT NULL,
  action       text        NOT NULL,
  repo         text        NOT NULL,
  issue_number bigint      NOT NULL,
  title        text        NOT NULL,
  html_url     text        NOT NULL,
  received_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dispatch_jobs (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id           bigint      NOT NULL UNIQUE REFERENCES webhook_events (id),
  status             text        NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SENT', 'FAILED')),
  attempts           integer     NOT NULL DEFAULT 0,
  next_attempt_at    timestamptz NOT NULL DEFAULT now(),
  locked_until       timestamptz,
  last_error         text,
  discord_message_id text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  sent_at            timestamptz
);

CREATE INDEX idx_dispatch_jobs_ready   ON dispatch_jobs (next_attempt_at) WHERE status IN ('PENDING', 'RETRY_WAIT');
CREATE INDEX idx_dispatch_jobs_expired ON dispatch_jobs (locked_until)    WHERE status = 'PROCESSING';

CREATE TABLE dispatch_attempts (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  job_id      bigint      NOT NULL REFERENCES dispatch_jobs (id),
  attempt_no  integer     NOT NULL,
  started_at  timestamptz NOT NULL,
  finished_at timestamptz,
  outcome     text CHECK (outcome IN ('SUCCESS', 'RETRYABLE_FAIL', 'PERMANENT_FAIL')),
  http_status integer,
  error       text,
  UNIQUE (job_id, attempt_no)
);
