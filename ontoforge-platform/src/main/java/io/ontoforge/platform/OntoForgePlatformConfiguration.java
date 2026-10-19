package io.ontoforge.platform;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Explicit module registration; the application does not scan arbitrary sibling packages. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = OntoForgePlatformConfiguration.class)
public class OntoForgePlatformConfiguration {}
