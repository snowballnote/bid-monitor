CREATE TABLE bid_source_discovery_result (
    source_id BIGINT PRIMARY KEY,
    discovery_status VARCHAR(30) NOT NULL,
    detected_collection_method VARCHAR(30) NOT NULL,
    list_page_url VARCHAR(2048),
    detail_url_pattern VARCHAR(4000),
    identifier_confidence VARCHAR(10) NOT NULL,
    title_confidence VARCHAR(10) NOT NULL,
    deadline_confidence VARCHAR(10) NOT NULL,
    attachment_detected BOOLEAN NOT NULL,
    pagination_detected BOOLEAN NOT NULL,
    reason_codes VARCHAR(2000) NOT NULL,
    analyzed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_bid_source_discovery_registration
        FOREIGN KEY (source_id) REFERENCES bid_source_registration (source_id) ON DELETE CASCADE,
    CONSTRAINT ck_bid_source_discovery_status
        CHECK (discovery_status IN ('ANALYZING', 'READY', 'MANUAL_REVIEW', 'UNSUPPORTED', 'FAILED')),
    CONSTRAINT ck_bid_source_discovery_method
        CHECK (detected_collection_method IN ('UNDETERMINED', 'OFFICIAL_API', 'PUBLIC_PAGE', 'RSS')),
    CONSTRAINT ck_bid_source_discovery_identifier_confidence
        CHECK (identifier_confidence IN ('NONE', 'LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_bid_source_discovery_title_confidence
        CHECK (title_confidence IN ('NONE', 'LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_bid_source_discovery_deadline_confidence
        CHECK (deadline_confidence IN ('NONE', 'LOW', 'MEDIUM', 'HIGH'))
);
