-- Schéma initial : conserve les tables existantes lors d'une mise à niveau.
CREATE TABLE IF NOT EXISTS utilisateurs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    nom VARCHAR(255) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_utilisateur_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS salles (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nom VARCHAR(255) NOT NULL,
    capacite INT NOT NULL,
    version INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS reservations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    salle_id BIGINT NOT NULL,
    utilisateur_id BIGINT NOT NULL,
    dateDebut DATETIME(6) NOT NULL,
    dateFin DATETIME(6) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_reservation_salle FOREIGN KEY (salle_id) REFERENCES salles(id),
    CONSTRAINT fk_reservation_utilisateur FOREIGN KEY (utilisateur_id) REFERENCES utilisateurs(id)
);
