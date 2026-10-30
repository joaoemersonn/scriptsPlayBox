package br.com.playbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Dados para disparo de atualização remota OTA do firmware do módulo")
public class AtualizarFirmwareDTO {

    @Schema(
            description = "URL direta HTTP ou HTTPS contendo o arquivo binário (.bin) compilado do novo firmware",
            example = "http://192.168.1.83:8080/firmware.bin",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String url;

    public AtualizarFirmwareDTO() {
    }

    public AtualizarFirmwareDTO(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
