package com.example;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationService;
import com.example.util.DataInitializer;
import com.example.util.JpaUtil;
import com.example.util.PerformanceReport;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.OptimisticLockException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MainApp {

    private static final Logger LOGGER = Logger.getLogger(MainApp.class.getName());

    public static void main(String[] args) throws Exception {
        EntityManagerFactory emf = null;
        try {
            System.out.println("=== Migration et démarrage ===");
            emf = JpaUtil.createFactory();
            EntityManager em = emf.createEntityManager();
            Long salleId;
            try {
                new DataInitializer(em).initData();
                salleId = em.createQuery("SELECT s.id FROM Salle s WHERE s.nom = :nom ORDER BY s.id", Long.class)
                        .setParameter("nom", "Salle A").setMaxResults(1).getSingleResult();
            } finally {
                em.close();
            }

            ReservationService service = new ReservationService(emf);
            System.out.println("\n=== Réservations initiales ===");
            afficher(service.findAll());
            demonstrationCRUD(emf, service, salleId);

            System.out.println("\n=== Recherches JPQL et Criteria ===");
            LocalDateTime date = LocalDateTime.now();
            List<Reservation> jpql = service.rechercherJPQL("Salle A", date);
            List<Reservation> criteria = service.rechercherCriteria("Salle A", date);
            if (!jpql.stream().map(Reservation::getId).collect(Collectors.toList())
                    .equals(criteria.stream().map(Reservation::getId).collect(Collectors.toList()))) {
                throw new IllegalStateException("JPQL et Criteria doivent donner les mêmes résultats.");
            }
            System.out.println("JPQL : " + jpql.size() + " | Criteria : " + criteria.size());
            afficher(jpql);

            demonstrationCache(emf, salleId);
            EntityManager rapportEm = emf.createEntityManager();
            try {
                new PerformanceReport(rapportEm).printReport();
            } finally {
                rapportEm.close();
            }
            System.out.println("Application terminée avec succès.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Échec de l'application : " + e.getMessage(), e);
            throw e;
        } finally {
            if (emf != null) emf.close();
            // Arrête aussi le thread de nettoyage du pilote lors d'une exécution Maven.
            com.mysql.cj.jdbc.AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }

    private static void demonstrationCRUD(EntityManagerFactory emf, ReservationService service, Long salleId) {
        EntityManager em = emf.createEntityManager();
        Reservation reservation = new Reservation();
        try {
            reservation.setSalle(em.find(Salle.class, salleId));
            reservation.setUtilisateur(em.createQuery("SELECT u FROM Utilisateur u WHERE u.email = :email",
                    Utilisateur.class).setParameter("email", "alice.dupont@example.com").getSingleResult());
        } finally {
            em.close();
        }
        LocalDateTime debut = LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.SECONDS);
        reservation.setDateDebut(debut);
        reservation.setDateFin(debut.plusHours(1));
        System.out.println("\n=== Création, modification et suppression ===");
        reservation = service.createReservation(reservation);
        Long id = reservation.getId();
        try {
            System.out.println("Réservation créée : " + id + " | version : " + reservation.getVersion());
            reservation.setDateDebut(debut.plusHours(2));
            reservation.setDateFin(debut.plusHours(3));
            reservation = service.updateReservation(reservation);
            System.out.println("Réservation modifiée | version : " + reservation.getVersion());

            System.out.println("\n=== Verrouillage optimiste ===");
            Reservation premiere = service.findById(id);
            Reservation seconde = service.findById(id);
            premiere.setDateFin(premiere.getDateFin().plusMinutes(15));
            premiere = service.updateReservation(premiere);
            System.out.println("Première modification réussie | version : " + premiere.getVersion());
            seconde.setDateFin(seconde.getDateFin().plusMinutes(30));
            try {
                service.updateReservation(seconde);
                throw new IllegalStateException("La version périmée aurait dû être rejetée.");
            } catch (OptimisticLockException e) {
                System.out.println("Conflit détecté : " + e.getMessage());
            }
        } finally {
            service.deleteReservation(id);
            System.out.println("Réservation supprimée : " + id);
        }
    }

    private static void demonstrationCache(EntityManagerFactory emf, Long salleId) {
        System.out.println("\n=== Cache L2 : deux EntityManager distincts ===");
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        emf.getCache().evict(Salle.class, salleId);
        long sqlAvant = stats.getPrepareStatementCount();
        lireSalle(emf, salleId);
        long sqlPremier = stats.getPrepareStatementCount() - sqlAvant;
        long hitsAvant = stats.getSecondLevelCacheHitCount();
        sqlAvant = stats.getPrepareStatementCount();
        lireSalle(emf, salleId);
        System.out.println("Premier chargement : " + sqlPremier + " instruction(s) SQL");
        System.out.println("Second chargement : " + (stats.getPrepareStatementCount() - sqlAvant)
                + " instruction(s) SQL | hits L2 : " + (stats.getSecondLevelCacheHitCount() - hitsAvant));

        System.out.println("\n=== Cache de requêtes ===");
        emf.unwrap(SessionFactory.class).getCache().evictQueryRegions();
        lireSallesCachees(emf);
        long hitsRequete = stats.getQueryCacheHitCount();
        lireSallesCachees(emf);
        System.out.println("Deuxième recherche | hits du cache de requêtes : "
                + (stats.getQueryCacheHitCount() - hitsRequete));
    }

    private static void lireSalle(EntityManagerFactory emf, Long id) {
        EntityManager em = emf.createEntityManager();
        try {
            System.out.println("Salle chargée : " + em.find(Salle.class, id).getNom());
        } finally {
            em.close();
        }
    }

    private static void lireSallesCachees(EntityManagerFactory emf) {
        EntityManager em = emf.createEntityManager();
        try {
            em.createQuery("SELECT s FROM Salle s ORDER BY s.id", Salle.class)
                    .setHint("org.hibernate.cacheable", true).getResultList();
        } finally {
            em.close();
        }
    }

    private static void afficher(List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            System.out.println("Réservation " + reservation.getId() + " | "
                    + reservation.getSalle().getNom() + " | " + reservation.getUtilisateur().getNom()
                    + " | " + reservation.getDateDebut() + " -> " + reservation.getDateFin());
        }
    }
}
