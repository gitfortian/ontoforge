package io.ontology.system.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** S0 application entry and future assembly point for independently built modules. */
@SpringBootApplication
public class OntologySystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(OntologySystemApplication.class, args);
    }
}
