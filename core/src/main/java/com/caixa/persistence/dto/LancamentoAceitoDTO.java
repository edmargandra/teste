package com.caixa.persistence.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record LancamentoAceitoDTO(@JsonProperty("id") UUID id) {
}
