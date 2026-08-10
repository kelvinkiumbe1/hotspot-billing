-- The owner's AI assistant (Groq), one row.
CREATE TABLE ai_settings (
    id       BIGINT      PRIMARY KEY,
    enabled  BOOLEAN     NOT NULL DEFAULT FALSE,
    api_key  VARCHAR(200),
    model    VARCHAR(80) NOT NULL DEFAULT 'llama-3.3-70b-versatile'
);

INSERT INTO ai_settings (id) VALUES (1);
