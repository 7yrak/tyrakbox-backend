CREATE TABLE IF NOT EXISTS upload_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    folder_id UUID NULL REFERENCES folders(id),
    file_id UUID NULL REFERENCES files(id),
    original_filename VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_upload_jobs_user_created_at
    ON upload_jobs (user_id, created_at DESC);
