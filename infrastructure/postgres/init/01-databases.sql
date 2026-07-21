CREATE ROLE keycloak LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;

CREATE ROLE userservice LOGIN PASSWORD 'userservice';
CREATE DATABASE userservice OWNER userservice;
