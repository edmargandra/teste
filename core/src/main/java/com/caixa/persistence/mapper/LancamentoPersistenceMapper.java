package com.caixa.persistence.mapper;

import com.caixa.application.LancamentoRepositoryPort;
import com.caixa.persistence.dto.LancamentoDTO;
import com.caixa.persistence.entity.LancamentoEntity;
import com.caixa.persistence.repository.LancamentoRepository;

public final class LancamentoPersistenceMapper {

    private LancamentoPersistenceMapper() {
    }

    public static LancamentoEntity toEntity(LancamentoDTO lancamento) {
        return new LancamentoEntity(
                lancamento.getId(),
                lancamento.getComercianteId(),
                lancamento.getData(),
                lancamento.getValor(),
                lancamento.getDirecao(),
                lancamento.getTipoPagamento(),
                lancamento.getDescricao(),
                lancamento.getComprovante());
    }

    public static LancamentoDTO toDomain(LancamentoEntity entity) {
        return new LancamentoDTO(
                entity.getId(),
                entity.getComercianteId(),
                entity.getData(),
                entity.getValor(),
                entity.getDirecao(),
                entity.getTipoPagamento(),
                entity.getDescricao(),
                entity.getComprovante());
    }

    public static LancamentoRepositoryPort.Linha toLinha(LancamentoRepository.LinhaListada linha) {
        return new LancamentoRepositoryPort.Linha(
                linha.id(),
                linha.data(),
                linha.valor(),
                linha.direcao(),
                linha.tipoPagamento(),
                linha.descricao(),
                linha.hasComprovante());
    }
}
