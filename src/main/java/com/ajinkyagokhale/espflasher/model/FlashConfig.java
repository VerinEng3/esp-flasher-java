package com.ajinkyagokhale.espflasher.model;

import java.util.List;

/**
 * Parâmetros de uma gravação. {@code esptoolCommand} é o comando já separado
 * em partes (ex.: [python.exe, -m, esptool]), para não depender de aspas
 * em caminhos com espaços.
 */
public record FlashConfig(
        String chip,
        int baudRate,
        String port,
        String binPath,
        String flashOffset,
        List<String> esptoolCommand
) {
    public FlashConfig {
        esptoolCommand = List.copyOf(esptoolCommand);
    }
}
