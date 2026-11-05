package com.caixa.persistence.adapter;

import com.caixa.application.SaldoRepositoryPort;
import com.caixa.persistence.dto.GrupoSaldoDTO;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class SaldoPersistenceAdapter implements SaldoRepositoryPort {

    private final EntityManager entityManager;

    public SaldoPersistenceAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GrupoSaldoDTO> porTipo(UUID comercianteId, LocalDate data) {
        List<Object[]> linhas = entityManager.createNativeQuery("""
                SELECT tipo_pagamento,
                       COALESCE(SUM(valor) FILTER (WHERE direcao = 'credito'), CAST(0 AS numeric)) AS creditos,
                       COALESCE(SUM(valor) FILTER (WHERE direcao = 'debito'), CAST(0 AS numeric)) AS debitos
                FROM lancamento
                WHERE comerciante_id = :comercianteId AND data = :data
                GROUP BY tipo_pagamento
                """)
                .setParameter("comercianteId", comercianteId)
                .setParameter("data", data)
                .getResultList();
        List<GrupoSaldoDTO> grupos = new ArrayList<>();
        for (Object[] linha : linhas) {
            grupos.add(new GrupoSaldoDTO(
                    (String) linha[0],
                    dinheiro((BigDecimal) linha[1]),
                    dinheiro((BigDecimal) linha[2])));
        }
        return grupos;
    }

    private static BigDecimal dinheiro(BigDecimal valor) {
        return (valor == null ? BigDecimal.ZERO : valor).setScale(2, RoundingMode.UNNECESSARY);
    }
}
