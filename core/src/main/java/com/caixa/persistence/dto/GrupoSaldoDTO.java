package com.caixa.persistence.dto;

import java.math.BigDecimal;

public record GrupoSaldoDTO(String tipoPagamento, BigDecimal creditos, BigDecimal debitos) {
}
