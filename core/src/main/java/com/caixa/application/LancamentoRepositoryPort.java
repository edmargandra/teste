package com.caixa.application;

import com.caixa.persistence.dto.LancamentoDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LancamentoRepositoryPort {

    void gravar(LancamentoDTO lancamento);

    Optional<LancamentoDTO> findByIdAndComercianteId(UUID id, UUID comercianteId);

    boolean corrigir(UUID id, UUID comercianteId, LocalDate data, BigDecimal valor,
                     String direcao, String tipoPagamento, String descricao, byte[] comprovante);

    List<Linha> listar(UUID comercianteId, boolean filtraData, LocalDate data,
                       boolean filtraTipo, String tipoPagamento,
                       boolean filtraDirecao, String direcao,
                       int page, int size);

    long contar(UUID comercianteId, boolean filtraData, LocalDate data,
                boolean filtraTipo, String tipoPagamento,
                boolean filtraDirecao, String direcao);

    record Linha(UUID id, LocalDate data, BigDecimal valor, String direcao,
                 String tipoPagamento, String descricao, boolean hasComprovante) {
    }
}
