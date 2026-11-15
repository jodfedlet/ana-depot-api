package com.anadepot.infra.config;

import com.anadepot.core.shared.CorrelationContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class MdcCorrelationContext implements CorrelationContext {

	public static final String MDC_KEY = "correlationId";

	@Override
	public String correlationId() {
		return MDC.get(MDC_KEY);
	}
}
