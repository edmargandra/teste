package com.caixa.adapter.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GrupoSaldoResponse(
        @JsonProperty("tipo_pagamento") String tipoPagamento,
        @JsonProperty("total de créditos") String totalDeCreditos,
        @JsonProperty("total de débitos") String totalDeDebitos,
        @JsonProperty("saldo do tipo") String saldoDoTipo) {
}
