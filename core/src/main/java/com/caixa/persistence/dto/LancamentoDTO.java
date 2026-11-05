package com.caixa.persistence.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class LancamentoDTO {

    private final UUID id;
    private final UUID comercianteId;
    private final LocalDate data;
    private final BigDecimal valor;
    private final String direcao;
    private final String tipoPagamento;
    private final String descricao;
    private final byte[] comprovante;

    public LancamentoDTO(UUID id, UUID comercianteId, LocalDate data, BigDecimal valor,
                         String direcao, String tipoPagamento, String descricao, byte[] comprovante) {
        this.id = id;
        this.comercianteId = comercianteId;
        this.data = data;
        this.valor = valor;
        this.direcao = direcao;
        this.tipoPagamento = tipoPagamento;
        this.descricao = descricao;
        this.comprovante = comprovante;
    }

    public UUID getId() {
        return id;
    }

    public UUID getComercianteId() {
        return comercianteId;
    }

    public LocalDate getData() {
        return data;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public String getDirecao() {
        return direcao;
    }

    public String getTipoPagamento() {
        return tipoPagamento;
    }

    public String getDescricao() {
        return descricao;
    }

    public byte[] getComprovante() {
        return comprovante;
    }

    public boolean hasComprovante() {
        return comprovante != null;
    }
}
