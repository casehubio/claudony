-- Created by Hibernate after drop-and-create schema generation.
-- ledger_subject_sequence is a native SQL table from V1000__ledger_base_schema.sql
-- (not a JPA entity), so Hibernate does not create it automatically.
CREATE TABLE IF NOT EXISTS ledger_subject_sequence (
    subject_id UUID NOT NULL PRIMARY KEY,
    next_seq   INT  NOT NULL DEFAULT 1
);
