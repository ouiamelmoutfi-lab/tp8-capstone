package com.example.test;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationService;
import com.example.util.DataInitializer;
import com.example.util.JpaUtil;
import com.example.util.MigrationExecutor;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.OptimisticLockException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

public class ReservationTest {

    private static EntityManagerFactory emf;
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private EntityManager em;
    private ReservationService service;
    private Salle salle;
    private Utilisateur user;
    private LocalDateTime debut;

    @BeforeAll
    public static void init() throws Exception {
        // Une base de test MySQL distincte protège les données de l'application.
        jdbcUrl = System.getProperty("reservation.test.jdbc.url",
                "jdbc:mysql://localhost:3306/reservation_salles_test?createDatabaseIfNotExist=true");
        username = System.getProperty("reservation.test.jdbc.user", "ouiam1");
        password = System.getProperty("reservation.test.jdbc.password", "Ouiam2003@");
        Map<String, Object> properties = new HashMap<>();
        properties.put("javax.persistence.jdbc.url", jdbcUrl);
        properties.put("javax.persistence.jdbc.user", username);
        properties.put("javax.persistence.jdbc.password", password);
        emf = JpaUtil.createFactory(properties);
    }

    @BeforeEach
    public void setUp() {
        em = emf.createEntityManager();
        service = new ReservationService(emf);
        debut = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        em.getTransaction().begin();
        user = new Utilisateur();
        user.setNom("Test User");
        user.setEmail("test." + UUID.randomUUID() + "@example.com");
        em.persist(user);
        salle = new Salle();
        salle.setNom("Salle Test " + UUID.randomUUID());
        salle.setCapacite(20);
        salle.setDescription("Salle créée pour un test");
        em.persist(salle);
        em.getTransaction().commit();
        em.clear();
    }

    @AfterEach
    public void tearDown() {
        if (em != null && em.isOpen()) {
            try {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                em.clear();
                em.getTransaction().begin();
                for (Reservation reservation : em.createQuery(
                        "SELECT r FROM Reservation r WHERE r.salle.id = :id", Reservation.class)
                        .setParameter("id", salle.getId()).getResultList()) {
                    em.remove(reservation);
                }
                em.remove(em.find(Salle.class, salle.getId()));
                em.remove(em.find(Utilisateur.class, user.getId()));
                em.getTransaction().commit();
            } finally {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                em.close();
            }
        }
    }

    @AfterAll
    public static void close() {
        if (emf != null && emf.isOpen()) emf.close();
    }

    @Test
    public void testCreateUpdateDeleteReservation() {
        Reservation reservation = service.createReservation(nouvelleReservation(debut, debut.plusHours(1)));
        Long id = reservation.getId();
        assertNotNull(id);
        Reservation lue = service.findById(id);
        assertEquals(debut, lue.getDateDebut());
        int ancienneVersion = lue.getVersion();
        LocalDateTime nouveauDebut = debut.plusDays(1);
        lue.setDateDebut(nouveauDebut);
        lue.setDateFin(nouveauDebut.plusHours(2));
        service.updateReservation(lue);
        Reservation modifiee = service.findById(id);
        assertEquals(nouveauDebut, modifiee.getDateDebut());
        assertTrue(modifiee.getVersion() > ancienneVersion);
        service.deleteReservation(id);
        assertNull(service.findById(id));
    }

    @Test
    public void testRefuseDatesInversees() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createReservation(nouvelleReservation(debut, debut.minusHours(1))));
    }

    @Test
    public void testRefuseDatesEgales() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createReservation(nouvelleReservation(debut, debut)));
    }

    @Test
    public void testRefuseDateAbsente() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createReservation(nouvelleReservation(null, debut)));
    }

    @Test
    public void testRefuseUtilisateurAbsent() {
        Reservation reservation = nouvelleReservation(debut, debut.plusHours(1));
        reservation.setUtilisateur(null);
        assertThrows(IllegalArgumentException.class, () -> service.createReservation(reservation));
    }

    @Test
    public void testRefuseCreneauEnConflitEtAnnuleTransaction() {
        service.createReservation(nouvelleReservation(debut, debut.plusHours(2)));
        assertThrows(IllegalArgumentException.class, () -> service.createReservation(
                nouvelleReservation(debut.plusHours(1), debut.plusHours(3))));
        assertEquals(1, service.rechercherJPQL(salle.getNom(), debut.minusSeconds(1)).size());
    }

    @Test
    public void testAccepteCreneauxConsecutifs() {
        service.createReservation(nouvelleReservation(debut, debut.plusHours(1)));
        service.createReservation(nouvelleReservation(debut.plusHours(1), debut.plusHours(2)));
        assertEquals(2, service.rechercherJPQL(salle.getNom(), debut.minusSeconds(1)).size());
    }

    @Test
    public void testConflitVersionDetachee() {
        Reservation creee = service.createReservation(nouvelleReservation(debut, debut.plusHours(1)));
        Reservation premiere = service.findById(creee.getId());
        Reservation seconde = service.findById(creee.getId());
        premiere.setDateFin(debut.plusHours(2));
        Reservation resultat = service.updateReservation(premiere);
        seconde.setDateFin(debut.plusHours(3));
        assertThrows(OptimisticLockException.class, () -> service.updateReservation(seconde));
        Reservation finale = service.findById(creee.getId());
        assertEquals(debut.plusHours(2), finale.getDateFin());
        assertEquals(resultat.getVersion(), finale.getVersion());
    }

    @Test
    public void testConflitDeuxTransactions() {
        Reservation creee = service.createReservation(nouvelleReservation(debut, debut.plusHours(1)));
        EntityManager em2 = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em2.getTransaction().begin();
            Reservation premiere = em.find(Reservation.class, creee.getId());
            Reservation seconde = em2.find(Reservation.class, creee.getId());
            premiere.setDateFin(debut.plusHours(2));
            em.getTransaction().commit();
            seconde.setDateFin(debut.plusHours(3));
            RuntimeException erreur = assertThrows(RuntimeException.class,
                    () -> em2.getTransaction().commit());
            boolean conflit = false;
            for (Throwable cause = erreur; cause != null; cause = cause.getCause()) {
                if (cause instanceof OptimisticLockException) conflit = true;
            }
            assertTrue(conflit, "Le second commit doit détecter le conflit de version.");
            assertEquals(debut.plusHours(2), service.findById(creee.getId()).getDateFin());
        } finally {
            if (em2.getTransaction().isActive()) em2.getTransaction().rollback();
            em2.close();
        }
    }

    @Test
    public void testJPQLEtCriteriaEquivalents() {
        Reservation reservation = service.createReservation(nouvelleReservation(debut, debut.plusHours(1)));
        List<Reservation> jpql = service.rechercherJPQL(salle.getNom(), debut.minusSeconds(1));
        List<Reservation> criteria = service.rechercherCriteria(salle.getNom(), debut.minusSeconds(1));
        assertEquals(List.of(reservation.getId()), jpql.stream().map(Reservation::getId).collect(Collectors.toList()));
        assertEquals(jpql.stream().map(Reservation::getId).collect(Collectors.toList()),
                criteria.stream().map(Reservation::getId).collect(Collectors.toList()));
        assertEquals(salle.getNom(), criteria.get(0).getSalle().getNom());
        assertTrue(service.rechercherJPQL(salle.getNom(), debut).isEmpty(),
                "La comparaison avec la date est strictement supérieure.");
    }

    @Test
    public void testCacheL2EntreDeuxEntityManagerEtMiseAJour() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        emf.getCache().evict(Salle.class, salle.getId());
        lireSalle();
        long hitsAvant = stats.getSecondLevelCacheHitCount();
        long sqlAvant = stats.getPrepareStatementCount();
        assertEquals(salle.getNom(), lireSalle().getNom());
        assertTrue(stats.getSecondLevelCacheHitCount() > hitsAvant);
        assertEquals(sqlAvant, stats.getPrepareStatementCount());

        em.getTransaction().begin();
        em.find(Salle.class, salle.getId()).setCapacite(35);
        em.getTransaction().commit();
        assertEquals(35, lireSalle().getCapacite(), "Le cache doit refléter la modification validée.");
    }

    @Test
    public void testCacheDeRequete() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        emf.unwrap(SessionFactory.class).getCache().evictQueryRegions();
        rechercherSalleCachee();
        long hitsAvant = stats.getQueryCacheHitCount();
        long sqlAvant = stats.getPrepareStatementCount();
        assertEquals(salle.getId(), rechercherSalleCachee().get(0).getId());
        assertTrue(stats.getQueryCacheHitCount() > hitsAvant);
        assertEquals(sqlAvant, stats.getPrepareStatementCount());
    }

    @Test
    public void testInitialisationNeDupliquePasLesDonnees() {
        new DataInitializer(em).initData();
        Long utilisateurs = em.createQuery("SELECT COUNT(u) FROM Utilisateur u", Long.class).getSingleResult();
        Long salles = em.createQuery("SELECT COUNT(s) FROM Salle s", Long.class).getSingleResult();
        Long reservations = em.createQuery("SELECT COUNT(r) FROM Reservation r", Long.class).getSingleResult();
        new DataInitializer(em).initData();
        assertEquals(utilisateurs, em.createQuery("SELECT COUNT(u) FROM Utilisateur u", Long.class).getSingleResult());
        assertEquals(salles, em.createQuery("SELECT COUNT(s) FROM Salle s", Long.class).getSingleResult());
        assertEquals(reservations, em.createQuery("SELECT COUNT(r) FROM Reservation r", Long.class).getSingleResult());
    }

    @Test
    public void testEmailUnique() {
        Utilisateur doublon = new Utilisateur();
        doublon.setNom("Doublon");
        doublon.setEmail(user.getEmail());
        em.getTransaction().begin();
        assertThrows(RuntimeException.class, () -> {
            em.persist(doublon);
            em.flush();
            em.getTransaction().commit();
        });
        if (em.getTransaction().isActive()) em.getTransaction().rollback();
    }

    @Test
    public void testMigrationEtReexecution() throws Exception {
        MigrationExecutor migration = new MigrationExecutor(jdbcUrl, username, password);
        migration.executeMigrations();
        migration.executeMigrations();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, "salles", "description")) {
                assertTrue(columns.next(), "La migration doit ajouter description.");
            }
            boolean indexPresent = false;
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), null, "utilisateurs", true, false)) {
                while (indexes.next()) {
                    if ("idx_utilisateur_email".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) indexPresent = true;
                }
            }
            assertTrue(indexPresent, "L'index unique demandé doit exister.");
        }
    }

    @Test
    public void testRepriseMigrationPartiellementAppliquee() throws Exception {
        // Simule un arrêt après les DDL, avant l'enregistrement de la migration.
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM schema_migrations WHERE script = "
                    + "'db/migration/v2_0_add_description.sql'");
        }
        new MigrationExecutor(jdbcUrl, username, password).executeMigrations();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
        }
        assertEquals("Salle créée pour un test", lireSalle().getDescription(),
                "La reprise doit conserver les données existantes.");
    }

    @Test
    public void testScriptIntrouvable() {
        MigrationExecutor migration = new MigrationExecutor(jdbcUrl, username, password);
        assertThrows(IOException.class, () -> migration.executeMigration("db/migration/introuvable.sql"));
    }

    private Reservation nouvelleReservation(LocalDateTime dateDebut, LocalDateTime dateFin) {
        Reservation reservation = new Reservation();
        reservation.setSalle(salle);
        reservation.setUtilisateur(user);
        reservation.setDateDebut(dateDebut);
        reservation.setDateFin(dateFin);
        return reservation;
    }

    private Salle lireSalle() {
        EntityManager lecture = emf.createEntityManager();
        try {
            return lecture.find(Salle.class, salle.getId());
        } finally {
            lecture.close();
        }
    }

    private List<Salle> rechercherSalleCachee() {
        EntityManager lecture = emf.createEntityManager();
        try {
            return lecture.createQuery("SELECT s FROM Salle s WHERE s.id = :id", Salle.class)
                    .setParameter("id", salle.getId()).setHint("org.hibernate.cacheable", true).getResultList();
        } finally {
            lecture.close();
        }
    }
}
