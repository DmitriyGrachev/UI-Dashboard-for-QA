CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE image_asset (
    id VARCHAR(64) PRIMARY KEY,
    file_name VARCHAR(512) NOT NULL,
    relative_path VARCHAR(1024) NOT NULL UNIQUE,
    file_created_at TIMESTAMPTZ NOT NULL,
    file_modified_at TIMESTAMPTZ NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    file_available BOOLEAN NOT NULL,
    game_code VARCHAR(100) NOT NULL,
    token_id BIGINT,
    session_uuid UUID,
    session_id VARCHAR(128),
    dealer_cards VARCHAR(512),
    active_user_cards VARCHAR(512),
    inactive_user_cards VARCHAR(512),
    payload_raw VARCHAR(2048),
    buttons_raw VARCHAR(512),
    is_notification BOOLEAN NOT NULL,
    has_stand BOOLEAN NOT NULL,
    has_hit BOOLEAN NOT NULL,
    has_double BOOLEAN NOT NULL,
    has_split BOOLEAN NOT NULL,
    processed_at TIMESTAMPTZ,
    recognition_duration_ms BIGINT,
    parse_status VARCHAR(16) NOT NULL,
    CONSTRAINT ck_image_asset_sha256
        CHECK (length(id) = 64 AND id ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_image_asset_parse_status
        CHECK (parse_status IN ('SUCCESS', 'PARTIAL', 'ERROR'))
);

CREATE TABLE review_task (
    image_id VARCHAR(64) PRIMARY KEY REFERENCES image_asset(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    assigned_to UUID REFERENCES app_user(id),
    assigned_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    decision VARCHAR(16),
    reviewed_at TIMESTAMPTZ,
    CONSTRAINT ck_review_task_status
        CHECK (status IN ('PENDING', 'ASSIGNED', 'COMPLETED')),
    CONSTRAINT ck_review_task_decision
        CHECK (decision IS NULL OR decision IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_review_task_completed
        CHECK (
            status <> 'COMPLETED'
            OR (decision IS NOT NULL AND assigned_to IS NOT NULL AND reviewed_at IS NOT NULL)
        )
);

CREATE INDEX ix_review_queue ON review_task (status, image_id);
CREATE INDEX ix_image_queue_order ON image_asset (file_created_at, id)
    WHERE file_available = TRUE;
CREATE INDEX ix_image_game ON image_asset (game_code);
CREATE INDEX ix_image_session ON image_asset (session_id);
CREATE INDEX ix_image_notification ON image_asset (is_notification);
CREATE INDEX ix_image_retention ON image_asset (file_created_at);
CREATE UNIQUE INDEX ux_review_one_active_per_operator
    ON review_task (assigned_to)
    WHERE status = 'ASSIGNED' AND assigned_to IS NOT NULL;
CREATE INDEX ix_review_expired ON review_task (lease_expires_at)
    WHERE status = 'ASSIGNED';
