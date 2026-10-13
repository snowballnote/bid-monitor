CREATE TABLE bid_collection_run_notice (
    run_id BIGINT NOT NULL,
    bid_notice_id BIGINT NOT NULL,
    CONSTRAINT pk_bid_collection_run_notice PRIMARY KEY (run_id, bid_notice_id),
    CONSTRAINT fk_bid_collection_run_notice_run
        FOREIGN KEY (run_id) REFERENCES bid_collection_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_bid_collection_run_notice_notice
        FOREIGN KEY (bid_notice_id) REFERENCES bid_notice (id) ON DELETE CASCADE
);

CREATE INDEX ix_bid_collection_run_notice_notice
    ON bid_collection_run_notice (bid_notice_id);
