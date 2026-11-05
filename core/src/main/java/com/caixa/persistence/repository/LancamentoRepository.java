package com.caixa.persistence.repository;

import com.caixa.persistence.entity.LancamentoEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LancamentoRepository extends JpaRepository<LancamentoEntity, UUID> {

    @Query("""
            SELECT new com.caixa.persistence.repository.LancamentoRepository$LinhaListada(
                l.id, l.data, l.valor, l.direcao, l.tipoPagamento, l.descricao, l.comprovante IS NOT NULL)
            FROM LancamentoEntity l
            WHERE l.comercianteId = :comercianteId
              AND (:filtraData = false OR l.data = :data)
              AND (:filtraTipo = false OR l.tipoPagamento = :tipoPagamento)
              AND (:filtraDirecao = false OR l.direcao = :direcao)
            ORDER BY l.id
            """)
    List<LinhaListada> listar(@Param("comercianteId") UUID comercianteId,
                              @Param("filtraData") boolean filtraData,
                              @Param("data") LocalDate data,
                              @Param("filtraTipo") boolean filtraTipo,
                              @Param("tipoPagamento") String tipoPagamento,
                              @Param("filtraDirecao") boolean filtraDirecao,
                              @Param("direcao") String direcao,
                              Pageable pageable);

    @Query("""
            SELECT COUNT(l)
            FROM LancamentoEntity l
            WHERE l.comercianteId = :comercianteId
              AND (:filtraData = false OR l.data = :data)
              AND (:filtraTipo = false OR l.tipoPagamento = :tipoPagamento)
              AND (:filtraDirecao = false OR l.direcao = :direcao)
            """)
    long contar(@Param("comercianteId") UUID comercianteId,
                @Param("filtraData") boolean filtraData,
                @Param("data") LocalDate data,
                @Param("filtraTipo") boolean filtraTipo,
                @Param("tipoPagamento") String tipoPagamento,
                @Param("filtraDirecao") boolean filtraDirecao,
                @Param("direcao") String direcao);

    Optional<LancamentoEntity> findByIdAndComercianteId(UUID id, UUID comercianteId);

    record LinhaListada(UUID id, LocalDate data, BigDecimal valor, String direcao,
                        String tipoPagamento, String descricao, boolean hasComprovante) {
    }
}
