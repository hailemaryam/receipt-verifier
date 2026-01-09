CREATE TABLE IF NOT EXISTS receiver_accounts (
    id BIGINT PRIMARY KEY,
    bankType VARCHAR(255) NOT NULL,
    accountNumber VARCHAR(255) NOT NULL,
    accountName VARCHAR(255) NOT NULL
);

-- Note: The id column is handled by Panache/Hibernate sequence by default for PanacheEntity.
-- In some DBs we might need a sequence:
CREATE SEQUENCE IF NOT EXISTS receiver_accounts_SEQ START WITH 1 INCREMENT BY 50;

-- Optional: Initial data from properties
-- Replace these with actual values from application.properties if needed, 
-- or just leave it empty for DB-driven setup.
