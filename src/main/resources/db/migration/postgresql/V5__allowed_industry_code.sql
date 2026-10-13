CREATE TABLE allowed_industry_code (
    code VARCHAR(4) PRIMARY KEY,
    created_at TIMESTAMP NOT NULL
);

INSERT INTO allowed_industry_code (code, created_at) VALUES
    ('6146', CURRENT_TIMESTAMP),
    ('1468', CURRENT_TIMESTAMP);
