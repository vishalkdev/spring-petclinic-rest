CREATE TABLE IF NOT EXISTS vets (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  first_name VARCHAR(30) NOT NULL,
  last_name VARCHAR(30) NOT NULL
);

CREATE INDEX idx_vets_last_name ON vets(last_name);

CREATE TABLE IF NOT EXISTS specialties (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name VARCHAR(80) NOT NULL
);

CREATE INDEX idx_specialties_name ON specialties(name);

CREATE TABLE IF NOT EXISTS vet_specialties (
  vet_id INTEGER NOT NULL,
  specialty_id INTEGER NOT NULL,
  FOREIGN KEY (vet_id) REFERENCES vets(id) ON DELETE CASCADE,
  FOREIGN KEY (specialty_id) REFERENCES specialties(id) ON DELETE CASCADE,
  UNIQUE (vet_id, specialty_id)
);

CREATE TABLE IF NOT EXISTS types (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name VARCHAR(80) NOT NULL
);

CREATE INDEX idx_types_name ON types(name);

CREATE TABLE IF NOT EXISTS owners (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  first_name VARCHAR(30) NOT NULL,
  last_name VARCHAR(30) NOT NULL,
  address VARCHAR(255) NOT NULL,
  city VARCHAR(80) NOT NULL,
  telephone VARCHAR(10) NOT NULL
);

CREATE INDEX idx_owners_last_name ON owners(last_name);

CREATE TABLE IF NOT EXISTS pets (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name VARCHAR(30) NOT NULL,
  birth_date DATE NOT NULL,
  type_id INTEGER NOT NULL,
  owner_id INTEGER NOT NULL,
  FOREIGN KEY (owner_id) REFERENCES owners(id) ON DELETE CASCADE,
  FOREIGN KEY (type_id) REFERENCES types(id) ON DELETE CASCADE
);

CREATE INDEX idx_pets_name ON pets(name);

CREATE TABLE IF NOT EXISTS visits (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  pet_id INTEGER NOT NULL,
  visit_date DATE NOT NULL,
  description VARCHAR(255) NOT NULL,
  FOREIGN KEY (pet_id) REFERENCES pets(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS users (
  username VARCHAR(20) NOT NULL PRIMARY KEY,
  password VARCHAR(255) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS roles (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username VARCHAR(20) NOT NULL,
  role VARCHAR(20) NOT NULL,
  UNIQUE (role, username),
  FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS outbox_event (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id        VARCHAR(36)   NOT NULL UNIQUE,
  subject         VARCHAR(64)   NOT NULL,
  event_type      VARCHAR(80)   NOT NULL,
  cause           VARCHAR(64),
  payload         VARCHAR(4000) NOT NULL,
  status          VARCHAR(16)   NOT NULL,
  attempts        INTEGER       DEFAULT 0 NOT NULL,
  next_attempt_at TIMESTAMP     NOT NULL,
  locked_until    TIMESTAMP,
  locked_by       VARCHAR(64),
  last_error      VARCHAR(1000),
  created_at      TIMESTAMP     NOT NULL,
  delivered_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_due ON outbox_event(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_outbox_subject ON outbox_event(subject, status, id);
