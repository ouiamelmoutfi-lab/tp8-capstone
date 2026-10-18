package com.example.util;

import com.example.model.*;
import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DataInitializer {

    private static final Logger LOGGER = Logger.getLogger(DataInitializer.class.getName());
    private final EntityManager em;

    public DataInitializer(EntityManager em) {
        this.em = em;
    }

    public void initData() {

        EntityTransaction tx = em.getTransaction();

        try {
            tx.begin();

            Long existants = em.createQuery(
                    "SELECT COUNT(u) FROM Utilisateur u WHERE u.email = :email", Long.class)
                    .setParameter("email", "alice.dupont@example.com").getSingleResult();
            if (existants > 0) {
                tx.commit();
                LOGGER.info("Le jeu de données est déjà présent.");
                return;
            }

            // Création des utilisateurs
            Utilisateur user1 = new Utilisateur();
            user1.setNom("Alice Dupont");
            user1.setEmail("alice.dupont@example.com");
            em.persist(user1);

            Utilisateur user2 = new Utilisateur();
            user2.setNom("Karim Benali");
            user2.setEmail("karim.benali@example.com");
            em.persist(user2);

            // Création des salles
            Salle salle1 = new Salle();
            salle1.setNom("Salle A");
            salle1.setCapacite(20);
            em.persist(salle1);

            Salle salle2 = new Salle();
            salle2.setNom("Salle B");
            salle2.setCapacite(40);
            em.persist(salle2);

            // Création des réservations
            LocalDateTime debut1 = LocalDateTime.now().plusDays(1);

            Reservation res1 = new Reservation();
            res1.setUtilisateur(user1);
            res1.setSalle(salle1);
            res1.setDateDebut(debut1);
            res1.setDateFin(debut1.plusHours(2));
            em.persist(res1);

            LocalDateTime debut2 = LocalDateTime.now().plusDays(2);

            Reservation res2 = new Reservation();
            res2.setUtilisateur(user2);
            res2.setSalle(salle2);
            res2.setDateDebut(debut2);
            res2.setDateFin(debut2.plusHours(3));
            em.persist(res2);

            tx.commit();

            System.out.println("Les données ont été initialisées avec succès.");

        } catch (RuntimeException e) {

            if (tx.isActive()) {
                tx.rollback();
            }

            LOGGER.log(Level.SEVERE, "Erreur lors de l'initialisation des données.", e);
            throw e;
        }
    }
}

