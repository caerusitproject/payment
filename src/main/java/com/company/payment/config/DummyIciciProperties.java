package com.company.payment.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Drives the behaviour of the dummy bank (prefix {@code icici.dummy}). */
@ConfigurationProperties(prefix = "icici.dummy")
public record DummyIciciProperties(String defaultScenario, Map<String, String> scenarioByBenAcctId) {}
