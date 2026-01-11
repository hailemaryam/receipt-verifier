ALTER TABLE verified_payments ADD COLUMN receiverAccount VARCHAR(255);
ALTER TABLE verified_payments ADD COLUMN receiverName VARCHAR(255);
ALTER TABLE verified_payments ADD COLUMN merchantReference VARCHAR(255);
