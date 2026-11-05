package com.caixa.persistence.adapter;

import com.caixa.application.LancamentoRepositoryPort;
import com.caixa.persistence.dto.LancamentoDTO;
import com.caixa.persistence.entity.LancamentoEntity;
import com.caixa.persistence.mapper.LancamentoPersistenceMapper;
import com.caixa.persistence.repository.LancamentoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LancamentoPersistenceAdapter implements LancamentoRepositoryPort {

    private final LancamentoRepository jpa;

    public LancamentoPersistenceAdapter(LancamentoRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void gravar(LancamentoDTO lancamento) {
        jpa.save(LancamentoPersistenceMapper.toEntity(lancamento));
    }

    @Override
    public Optional<LancamentoDTO> findByIdAndComercianteId(UUID id, UUID comercianteId) {
        return jpa.findByIdAndComercianteId(id, comercianteId).map(LancamentoPersistenceMapper::toDomain);
    }

    @Override
    public boolean corrigir(UUID id, UUID comercianteId, LocalDate data, BigDecimal valor,
                            String direcao, String tipoPagamento, String descricao, byte[] comprovante) {
        Optional<LancamentoEntity> encontrado = jpa.findByIdAndComercianteId(id, comercianteId);
        if (encontrado.isEmpty()) {
            return false;
        }
        encontrado.get().corrigir(data, valor, direcao, tipoPagamento, descricao, comprovante);
        return true;
    }

    @Override
    public List<Linha> listar(UUID comercianteId, boolean filtraData, LocalDate data,
                              boolean filtraTipo, String tipoPagamento,
                              boolean filtraDirecao, String direcao,
                              int page, int size) {
        return jpa.listar(comercianteId, filtraData, data, filtraTipo, tipoPagamento, filtraDirecao, direcao,
                        PageRequest.of(page, size))
                .stream()
                .map(LancamentoPersistenceMapper::toLinha)
                .toList();
    }

    @Override
    public long contar(UUID comercianteId, boolean filtraData, LocalDate data,
                       boolean filtraTipo, String tipoPagamento,
                       boolean filtraDirecao, String direcao) {
        return jpa.contar(comercianteId, filtraData, data, filtraTipo, tipoPagamento, filtraDirecao, direcao);
    }
}
