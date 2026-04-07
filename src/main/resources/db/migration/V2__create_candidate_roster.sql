-- Candidate Roster — per-assessor address book of participant emails
-- Matches schema.md Section 1 (candidate_roster)

CREATE TABLE IF NOT EXISTS candidate_roster (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    assessor_id UUID         NOT NULL REFERENCES users(id),
    name        VARCHAR(255) NOT NULL,
    email       VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_roster_assessor_email UNIQUE (assessor_id, email)
);

CREATE INDEX idx_candidate_roster_assessor_id ON candidate_roster(assessor_id);
