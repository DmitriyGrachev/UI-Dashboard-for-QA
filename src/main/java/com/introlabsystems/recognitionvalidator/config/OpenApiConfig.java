package com.introlabsystems.recognitionvalidator.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Recognition Validator Integration API",
        version = "1.0",
        description = "Pull-based API for independent AI screenshot review. API operations require X-API-Key."
))
@SecurityScheme(name = OpenApiConfig.INTEGRATION_API_KEY, type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER, paramName = "X-API-Key")
public class OpenApiConfig {
    public static final String INTEGRATION_API_KEY = "IntegrationApiKey";
}
