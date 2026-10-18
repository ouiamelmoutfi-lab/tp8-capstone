package com.example.util;

import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import javax.persistence.EntityManager;

public class PerformanceReport {

    private final EntityManager em;

    public PerformanceReport(EntityManager em) {
        this.em = em;
    }

    public void printReport() {
        Session session = em.unwrap(Session.class);
        Statistics stats = session.getSessionFactory().getStatistics();
        long dureeTotale = 0;
        for (String query : stats.getQueries()) {
            dureeTotale += stats.getQueryStatistics(query).getExecutionTotalTime();
        }
        long hit = stats.getSecondLevelCacheHitCount();
        long miss = stats.getSecondLevelCacheMissCount();
        double ratio = hit + miss > 0 ? (double) hit / (hit + miss) : 0;

        System.out.println("\n=== RAPPORT DE PERFORMANCE ===");
        System.out.println("Requêtes JPQL/Criteria exécutées : " + stats.getQueryExecutionCount());
        System.out.println("Instructions SQL préparées : " + stats.getPrepareStatementCount());
        System.out.println("Cache de second niveau hits : " + hit);
        System.out.println("Cache de second niveau misses : " + miss);
        System.out.println("Cache de second niveau mises en cache : " + stats.getSecondLevelCachePutCount());
        System.out.printf("Hit ratio du cache L2 : %.2f %%\n", ratio * 100);
        System.out.println("Cache de requêtes hits : " + stats.getQueryCacheHitCount());
        System.out.println("Cache de requêtes misses : " + stats.getQueryCacheMissCount());
        System.out.println("Transactions : " + stats.getTransactionCount());
        System.out.println("Transactions réussies : " + stats.getSuccessfulTransactionCount());
        System.out.println("Durée cumulée des requêtes suivies (ms) : " + dureeTotale);
    }
}
