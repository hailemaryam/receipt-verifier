CREATE TABLE verified_payments (
    id BIGINT PRIMARY KEY,
    senderId VARCHAR(255) NOT NULL,
    reference VARCHAR(255) NOT NULL,
    bankType VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2),
    payerName VARCHAR(255),
    transactionDate TIMESTAMP,
    rawData TEXT,
    verifiedAt TIMESTAMP
);

CREATE SEQUENCE verified_payments_seq START WITH 1 INCREMENT BY 50;
