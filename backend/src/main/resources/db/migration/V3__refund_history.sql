CREATE TABLE refund_history (
  refund_id VARCHAR(100) NOT NULL REFERENCES refund_request(id),
  version INT NOT NULL, status VARCHAR(30) NOT NULL,
  reason VARCHAR(500) NOT NULL, review_note VARCHAR(500) NOT NULL,
  actor_id VARCHAR(100) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (refund_id,version)
);
INSERT INTO refund_history(refund_id,version,status,reason,review_note,actor_id,created_at)
SELECT id,version,status,reason,review_note,COALESCE(reviewer_id,owner_id),COALESCE(reviewed_at,confirmed_at,created_at)
FROM refund_request;
