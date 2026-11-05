package com.caixa.adapter.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SaldoDiarioResponse(
        @JsonProperty("grupos") List<GrupoSaldoResponse> grupos,
        @JsonProperty("saldo da data") String saldoDaData) {
}
