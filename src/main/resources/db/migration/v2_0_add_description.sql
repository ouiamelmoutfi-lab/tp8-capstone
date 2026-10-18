-- Migration 2.0 : description facultative, sans perte des salles existantes.
ALTER TABLE salles ADD COLUMN description VARCHAR(255);

-- Index demandé dans le TP pour les recherches par email et son unicité.
CREATE UNIQUE INDEX idx_utilisateur_email ON utilisateurs(email);
