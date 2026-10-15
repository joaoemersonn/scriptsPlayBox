package br.com.playbox.client;

public class IntegrationException extends RuntimeException {

    private final String serviceName;
    private final String details;

    public IntegrationException(String serviceName, String details) {
        super(buildMessage(serviceName, details));
        this.serviceName = serviceName != null ? serviceName : "desconhecido";
        this.details = details;
    }

    private static String buildMessage(String serviceName, String details) {
        String base = "Erro ao obter dados integracao: " + (serviceName != null ? serviceName : "desconhecido");
        if (details != null && !details.isBlank()) {
            return base + "\n" + details;
        }
        return base;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getDetails() {
        return details;
    }
}

