-- Database-per-service provisioning. Runs once on a fresh Postgres volume
-- (docker-entrypoint-initdb.d). Each microservice owns its own database.
CREATE DATABASE core_db;

-- Shared LuckPerms storage so groups/prefixes are network-wide (all lobbies).
CREATE DATABASE luckperms_db;

-- Future per-service databases (uncomment when the service is split out):
-- CREATE DATABASE auth_db;
-- CREATE DATABASE profile_db;
-- CREATE DATABASE inventory_db;
-- CREATE DATABASE ranking_db;
