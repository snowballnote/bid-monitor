CREATE TABLE bid_candidate_evaluation_lock (
    id INTEGER PRIMARY KEY CHECK (id = 1)
);
INSERT INTO bid_candidate_evaluation_lock (id) VALUES (1);
