package com.example.service;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.LockModeType;
import javax.persistence.OptimisticLockException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ReservationService {

    private static final Logger LOGGER = Logger.getLogger(ReservationService.class.getName());
    private final EntityManagerFactory emf;

    public ReservationService(EntityManagerFactory emf) {
        this.emf = emf;
    }

    public Reservation createReservation(Reservation reservation) {
        verifierDates(reservation);
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            verifierRelationsEtDisponibilite(em, reservation);
            em.persist(reservation);
            tx.commit();
            LOGGER.info("Réservation créée : " + reservation.getId());
            return reservation;
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw signalerErreur("création", e);
        } finally {
            em.close();
        }
    }

    public Reservation updateReservation(Reservation reservation) {
        verifierDates(reservation);
        if (reservation.getId() == null) {
            throw new IllegalArgumentException("La réservation doit avoir un identifiant.");
        }
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            if (em.find(Reservation.class, reservation.getId()) == null) {
                throw new IllegalArgumentException("Réservation introuvable : " + reservation.getId());
            }
            verifierRelationsEtDisponibilite(em, reservation);
            // merge conserve la version de l'objet détaché et détecte une version périmée.
            Reservation resultat = em.merge(reservation);
            tx.commit();
            LOGGER.info("Réservation modifiée : " + resultat.getId());
            return resultat;
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw signalerErreur("modification", e);
        } finally {
            em.close();
        }
    }

    public void deleteReservation(Long id) {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Reservation reservation = em.find(Reservation.class, id);
            if (reservation == null) {
                throw new IllegalArgumentException("Réservation introuvable : " + id);
            }
            em.remove(reservation);
            tx.commit();
            LOGGER.info("Réservation supprimée : " + id);
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw signalerErreur("suppression", e);
        } finally {
            em.close();
        }
    }

    public Reservation findById(Long id) {
        EntityManager em = emf.createEntityManager();
        try {
            List<Reservation> resultats = em.createQuery(
                    "SELECT r FROM Reservation r JOIN FETCH r.salle JOIN FETCH r.utilisateur WHERE r.id = :id",
                    Reservation.class).setParameter("id", id).getResultList();
            return resultats.isEmpty() ? null : resultats.get(0);
        } finally {
            em.close();
        }
    }

    public List<Reservation> findAll() {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT r FROM Reservation r JOIN FETCH r.salle "
                    + "JOIN FETCH r.utilisateur ORDER BY r.dateDebut, r.id", Reservation.class).getResultList();
        } finally {
            em.close();
        }
    }

    public List<Reservation> rechercherJPQL(String nomSalle, LocalDateTime date) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT r FROM Reservation r JOIN FETCH r.salle JOIN FETCH r.utilisateur "
                    + "WHERE r.salle.nom = :nomSalle AND r.dateDebut > :date ORDER BY r.dateDebut, r.id",
                    Reservation.class).setParameter("nomSalle", nomSalle)
                    .setParameter("date", date).getResultList();
        } finally {
            em.close();
        }
    }

    public List<Reservation> rechercherCriteria(String nomSalle, LocalDateTime date) {
        EntityManager em = emf.createEntityManager();
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Reservation> cq = cb.createQuery(Reservation.class);
            Root<Reservation> root = cq.from(Reservation.class);
            root.fetch("salle");
            root.fetch("utilisateur");
            cq.select(root).where(cb.and(
                    cb.equal(root.get("salle").get("nom"), nomSalle),
                    cb.greaterThan(root.<LocalDateTime>get("dateDebut"), date)))
                    .orderBy(cb.asc(root.get("dateDebut")), cb.asc(root.get("id")));
            return em.createQuery(cq).getResultList();
        } finally {
            em.close();
        }
    }

    private void verifierDates(Reservation reservation) {
        if (reservation == null || reservation.getDateDebut() == null || reservation.getDateFin() == null) {
            throw new IllegalArgumentException("Les dates de réservation sont obligatoires.");
        }
        if (!reservation.getDateFin().isAfter(reservation.getDateDebut())) {
            throw new IllegalArgumentException("La date de fin doit être après la date de début.");
        }
    }

    private void verifierRelationsEtDisponibilite(EntityManager em, Reservation reservation) {
        if (reservation.getSalle() == null || reservation.getSalle().getId() == null
                || reservation.getUtilisateur() == null || reservation.getUtilisateur().getId() == null) {
            throw new IllegalArgumentException("Une salle et un utilisateur existants sont obligatoires.");
        }
        Salle salle = em.find(Salle.class, reservation.getSalle().getId());
        Utilisateur utilisateur = em.find(Utilisateur.class, reservation.getUtilisateur().getId());
        if (salle == null || utilisateur == null) {
            throw new IllegalArgumentException("Salle ou utilisateur introuvable.");
        }
        // Deux créations concurrentes doivent aussi se départager sur la version de la salle.
        em.lock(salle, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        String jpql = "SELECT COUNT(r) FROM Reservation r WHERE r.salle.id = :salleId "
                + "AND r.dateDebut < :fin AND r.dateFin > :debut";
        if (reservation.getId() != null) jpql += " AND r.id <> :id";
        javax.persistence.TypedQuery<Long> query = em.createQuery(jpql, Long.class)
                .setParameter("salleId", salle.getId())
                .setParameter("fin", reservation.getDateFin())
                .setParameter("debut", reservation.getDateDebut());
        if (reservation.getId() != null) query.setParameter("id", reservation.getId());
        if (query.getSingleResult() > 0) {
            throw new IllegalArgumentException("La salle est déjà réservée sur ce créneau.");
        }
        reservation.setSalle(salle);
        reservation.setUtilisateur(utilisateur);
    }

    private RuntimeException signalerErreur(String operation, RuntimeException e) {
        LOGGER.log(Level.WARNING, "Échec de " + operation + " de la réservation : " + e.getMessage());
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException) {
                return new OptimisticLockException("Conflit de version, rechargez la réservation puis réessayez.", e);
            }
        }
        return e;
    }
}
