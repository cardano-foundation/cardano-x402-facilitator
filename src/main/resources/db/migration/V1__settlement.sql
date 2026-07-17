CREATE SCHEMA IF NOT EXISTS facilitator;

CREATE TABLE IF NOT EXISTS facilitator.settlement (
    tx_hash             varchar(64) PRIMARY KEY,
    attempt_id          uuid        NOT NULL,
    requirements_digest varchar(64) NOT NULL,
    network             varchar(64) NOT NULL,
    status              varchar(16) NOT NULL,
    payer               varchar(256),
    pay_to              varchar(256),
    asset               varchar(128),
    amount              numeric,
    transfer_method     varchar(32),
    nonce_outref        varchar(80),
    tx_ttl_slot         bigint,
    claimed_at          timestamp with time zone NOT NULL,
    submitted_at        timestamp with time zone,
    confirmed_at        timestamp with time zone,
    confirmed_slot      bigint,
    confirmed_block     varchar(64),
    error_reason        varchar(128),
    response_json       text
);

CREATE INDEX IF NOT EXISTS idx_settlement_status_claimed
    ON facilitator.settlement (status, claimed_at);
