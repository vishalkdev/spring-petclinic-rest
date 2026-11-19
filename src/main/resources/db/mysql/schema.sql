CREATE TABLE IF NOT EXISTS vets (
  id INT(4) UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  first_name VARCHAR(30),
  last_name VARCHAR(30),
  INDEX(last_name)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS specialties (
  id INT(4) UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(80),
  INDEX(name)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS vet_specialties (
  vet_id INT(4) UNSIGNED NOT NULL,
  specialty_id INT(4) UNSIGNED NOT NULL,
  FOREIGN KEY (vet_id) REFERENCES vets(id),
  FOREIGN KEY (specialty_id) REFERENCES specialties(id),
  UNIQUE (vet_id,specialty_id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS types (
  id INT(4) UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(80),
  INDEX(name)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS owners (
  id INT(4) UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  first_name VARCHAR(30),
  last_name VARCHAR(30),
  address VARCHAR(255),
  city VARCHAR(80),
  telephone VARCHAR(10) NOT NULL,
  INDEX(last_name)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS pets (
  id INT(4) UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(30) NOT NULL,
  birth_date DATE NOT NULL,
  type_id INT(4) UNSIGNED NOT NULL,
  owner_id INT(4) UNSIGNED NOT NULL,
  INDEX(name),
  FOREIGN KEY (owner_id) REFERENCES owners(id),
  FOREIGN KEY (type_id) REFERENCES types(id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS visits (
  id INT(4) UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  pet_id INT(4) UNSIGNED NOT NULL,
  visit_date DATE NOT NULL,
  description VARCHAR(255) NOT NULL,
  FOREIGN KEY (pet_id) REFERENCES pets(id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS users (
  username VARCHAR(20) NOT NULL ,
  password VARCHAR(60) NOT NULL ,
  enabled TINYINT NOT NULL DEFAULT 1 ,
  PRIMARY KEY (username)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS roles (
  id int(11) NOT NULL AUTO_INCREMENT,
  username varchar(20) NOT NULL,
  role varchar(20) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uni_username_role (role,username),
  KEY fk_username_idx (username),
  CONSTRAINT fk_username FOREIGN KEY (username) REFERENCES users (username)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS outbox_event (
  id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id        VARCHAR(36)   NOT NULL UNIQUE,
  subject         VARCHAR(64)   NOT NULL,
  event_type      VARCHAR(80)   NOT NULL,
  cause           VARCHAR(64),
  payload         VARCHAR(4000) NOT NULL,
  status          VARCHAR(16)   NOT NULL,
  attempts        INTEGER       DEFAULT 0 NOT NULL,
  next_attempt_at DATETIME(6)     NOT NULL,
  locked_until    DATETIME(6),
  locked_by       VARCHAR(64),
  last_error      VARCHAR(1000),
  created_at      DATETIME(6)     NOT NULL,
  delivered_at    DATETIME(6),
  INDEX idx_outbox_due (status, next_attempt_at),
  INDEX idx_outbox_subject (subject, status, id)
) engine=InnoDB;
