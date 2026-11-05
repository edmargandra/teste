package com.caixa.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "lancamento")
public class LancamentoEntity {

    @Id
    private UUID id;

    @Column(name = "comerciante_id", nullable = false)
    private UUID comercianteId;

    @Column(name = "data", nullable = false)
    private LocalDate data;

    @Column(name = "valor", nullable = false, precision = 19, scale = 2)
    private BigDecimal valor;

    @Column(name = "direcao", nullable = false)
    private String direcao;

    @Column(name = "tipo_pagamento", nullable = false)
    private String tipoPagamento;

    @Column(name = "descricao", nullable = false)
    private String descricao;

    @Column(name = "comprovante")
    private byte[] comprovante;

    protected LancamentoEntity() {
    }

    public LancamentoEntity(UUID id, UUID comercianteId, LocalDate data, BigDecimal valor,
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

    public void corrigir(LocalDate data, BigDecimal valor, String direcao, String tipoPagamento,
                         String descricao, byte[] comprovante) {
        this.data = data;
        this.valor = valor;
        this.direcao = direcao;
        this.tipoPagamento = tipoPagamento;
        this.descricao = descricao;
        if (comprovante != null && comprovante.length > 0) {
            this.comprovante = comprovante;
        }
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
}
