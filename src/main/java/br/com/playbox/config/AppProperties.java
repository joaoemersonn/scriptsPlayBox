package br.com.playbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String dataDir = "./data";
    private int timeoutSeconds = 30;
    private String apiKey = "playbox_secure_api_key_2026";

    private final Whatsapp whatsapp = new Whatsapp();
    private final Vendpromax vendpromax = new Vendpromax();
    private final Payxyz payxyz = new Payxyz();
    private final Zapizi zapizi = new Zapizi();

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Whatsapp getWhatsapp() {
        return whatsapp;
    }

    public Vendpromax getVendpromax() {
        return vendpromax;
    }

    public Payxyz getPayxyz() {
        return payxyz;
    }

    public Zapizi getZapizi() {
        return zapizi;
    }

    public static class Whatsapp {
        private String host = "whatsapp-bot";
        private int port = 3035;
        private String baseUrl = "http://whatsapp-bot:3035";
        private String apiKey = "";
        private String sendPath = "/api/messages/send";
        private String monitorChatId = "";
        private String reportChatId = "";
        private String estoqueChatId = "120363423381929568@g.us";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSendPath() {
            return sendPath;
        }

        public void setSendPath(String sendPath) {
            this.sendPath = sendPath;
        }

        public String getMonitorChatId() {
            return monitorChatId;
        }

        public void setMonitorChatId(String monitorChatId) {
            this.monitorChatId = monitorChatId;
        }

        public String getReportChatId() {
            return reportChatId;
        }

        public void setReportChatId(String reportChatId) {
            this.reportChatId = reportChatId;
        }

        public String getEstoqueChatId() {
            return estoqueChatId;
        }

        public void setEstoqueChatId(String estoqueChatId) {
            this.estoqueChatId = estoqueChatId;
        }
    }

    public static class Vendpromax {
        private String baseUrl = "https://api.vendpromax.com.br/api";
        private String email = "";
        private String password = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Payxyz {
        private String baseUrl = "https://api2.payxyz.app.br/v2";
        private String email = "";
        private String password = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Zapizi {
        private String baseUrl = "https://portal.zapizi.com.br/v1";
        private String email = "";
        private String password = "";
        private Integer organizationId = 3852;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Integer getOrganizationId() {
            return organizationId;
        }

        public void setOrganizationId(Integer organizationId) {
            this.organizationId = organizationId;
        }
    }
}
