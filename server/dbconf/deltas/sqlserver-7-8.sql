ALTER TABLE CONFIGURATION ALTER COLUMN VALUE TEXT NULL

INSERT INTO CONFIGURATION (CATEGORY, NAME, VALUE) VALUES ('core', 'server.maxqueuesize', '100000')