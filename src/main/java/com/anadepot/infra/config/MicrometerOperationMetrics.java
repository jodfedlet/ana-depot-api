package com.anadepot.infra.config;

import com.anadepot.core.shared.OperationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business operation counters, exposed through the actuator metrics endpoint as {@code anadepot.operations}. */
@Component
public class MicrometerOperationMetrics implements OperationMetrics {

	private final MeterRegistry registry;

	public MicrometerOperationMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void count(String name, String... tagKeyValues) {
		var tags = new java.util.ArrayList<String>(java.util.List.of("operation", name));
		tags.addAll(java.util.List.of(tagKeyValues));
		registry.counter("anadepot.operations", tags.toArray(new String[0])).increment();
	}
}
