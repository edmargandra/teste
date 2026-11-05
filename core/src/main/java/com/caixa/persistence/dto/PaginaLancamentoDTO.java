package com.caixa.persistence.dto;

import com.caixa.adapter.response.LinhaLancamentoResponse;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PaginaLancamentoDTO(
        @JsonProperty("linhas") List<LinhaLancamentoResponse> linhas,
        @JsonProperty("total") long total) {
}
