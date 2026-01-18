CREATE TABLE failed_verifications (
    id BIGINT PRIMARY KEY,
    senderId VARCHAR(255),
    reference VARCHAR(255),
    bankType VARCHAR(255),
    reason TEXT,
    merchantReferenceId VARCHAR(255),
    failedAt TIMESTAMP
);

CREATE SEQUENCE failed_verifications_seq START WITH 1 INCREMENT BY 50;
