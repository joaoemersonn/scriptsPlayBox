package br.com.playbox.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "ApiKeyAuth";

    @Bean
    public OpenAPI playBoxOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PlayBox Scheduler & Monitor API")
                        .description("API REST para monitoramento de máquinas offline e disparo sob demanda de relatórios de faturamento das plataformas VendProMax, PayXYZ e Zapizi.")
                        .version("1.0.0")
                        .contact(new Contact().name("PlayBox").email("joaoniculau@gmail.com"))
                        .license(new License().name("Proprietary")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name("X-API-KEY")
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("Chave de API necessária para acessar os métodos protegidos da aplicação")));
    }
}

