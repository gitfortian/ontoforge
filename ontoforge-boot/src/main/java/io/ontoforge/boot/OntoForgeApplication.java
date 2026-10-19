package io.ontoforge.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** S0 application entry and future assembly point for independently built modules. */
@org.springframework.context.annotation.Import({
    io.ontoforge.dao.OntoForgePersistenceConfiguration.class,
    io.ontoforge.platform.OntoForgePlatformConfiguration.class,
    io.ontoforge.business.OntoForgeBusinessConfiguration.class
})
@SpringBootApplication
public class OntoForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OntoForgeApplication.class, args);
    }
}
