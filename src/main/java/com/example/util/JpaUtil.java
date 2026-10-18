package com.example.util;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class JpaUtil {

    private JpaUtil() {
    }

    public static EntityManagerFactory createFactory() throws Exception {
        return createFactory(new HashMap<>());
    }

    public static EntityManagerFactory createFactory(Map<String, Object> overrides) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        try (InputStream input = JpaUtil.class.getClassLoader()
                .getResourceAsStream("META-INF/persistence.xml")) {
            if (input == null) {
                throw new IllegalStateException("META-INF/persistence.xml est introuvable.");
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList nodes = factory.newDocumentBuilder().parse(input).getElementsByTagName("property");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element property = (Element) nodes.item(i);
                properties.put(property.getAttribute("name"), property.getAttribute("value"));
            }
        }
        // Les valeurs par défaut restent celles de persistence.xml.
        properties.put("javax.persistence.jdbc.url", System.getProperty("reservation.jdbc.url",
                properties.get("javax.persistence.jdbc.url").toString()));
        properties.put("javax.persistence.jdbc.user", System.getProperty("reservation.jdbc.user",
                properties.get("javax.persistence.jdbc.user").toString()));
        properties.put("javax.persistence.jdbc.password", System.getProperty("reservation.jdbc.password",
                properties.get("javax.persistence.jdbc.password").toString()));
        properties.putAll(overrides);

        Class.forName(properties.get("javax.persistence.jdbc.driver").toString());
        new MigrationExecutor(properties.get("javax.persistence.jdbc.url").toString(),
                properties.get("javax.persistence.jdbc.user").toString(),
                properties.get("javax.persistence.jdbc.password").toString()).executeMigrations();
        // Migration avant Hibernate pour que celui-ci ne crée pas déjà la colonne description.
        return Persistence.createEntityManagerFactory("ReservationPU", properties);
    }
}
