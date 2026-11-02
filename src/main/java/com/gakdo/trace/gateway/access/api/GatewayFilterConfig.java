package com.gakdo.trace.gateway.access.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayFilterConfig {

    @Bean
    public TraceHeadersGlobalFilter traceHeadersGlobalFilter() {
        return new TraceHeadersGlobalFilter();
    }
}
