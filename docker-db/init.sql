-- 1. Create role and database for Orders
CREATE ROLE orders WITH LOGIN PASSWORD 'orders';
CREATE DATABASE orders WITH OWNER orders;

-- Connect to the orders database to set up schema permissions
\c orders;

GRANT ALL ON SCHEMA public TO orders;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO orders;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO orders;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON ROUTINES TO orders;

-- 2. Create role and database for Keycloak
CREATE ROLE keycloak WITH LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak WITH OWNER keycloak;

-- Connect to the keycloak database to set up schema permissions
\c keycloak;

GRANT ALL ON SCHEMA public TO keycloak;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO keycloak;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO keycloak;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON ROUTINES TO keycloak;
