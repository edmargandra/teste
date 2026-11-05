package com.caixa.adapter.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LinhaLancamentoResponse(
        @JsonProperty("id") String id,
        @JsonProperty("data") String data,
        @JsonProperty("valor") String valor,
        @JsonProperty("direcao") String direcao,
        @JsonProperty("tipo_pagamento") String tipoPagamento,
        @JsonProperty("descricao") String descricao,
        @JsonProperty("comprovante") boolean comprovante) {
}
