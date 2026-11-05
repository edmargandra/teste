package com.caixa.service;

import com.caixa.exception.RecusaLancamentoException;
import com.caixa.application.LancamentoRepositoryPort;
import com.caixa.application.LancamentoRepositoryPort.Linha;
import com.caixa.persistence.dto.LancamentoDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class LancamentoService {

    private final LancamentoRepositoryPort repositorio;

    public LancamentoService(LancamentoRepositoryPort repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional
    public void gravar(UUID id, UUID comercianteId, LocalDate data, BigDecimal valor,
                       String direcao, String tipoPagamento, String descricao, byte[] comprovante) {
        repositorio.gravar(new LancamentoDTO(
                id, comercianteId, data, valor, direcao, tipoPagamento, descricao, comprovante));
    }

    @Transactional
    public void corrigir(UUID id, UUID comercianteId, LocalDate data, BigDecimal valor,
                         String direcao, String tipoPagamento, String descricao, byte[] comprovante) {
        if (!repositorio.corrigir(id, comercianteId, data, valor, direcao, tipoPagamento, descricao, comprovante)) {
            throw new RecusaLancamentoException("lancamento ausente");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Pagina listar(UUID comercianteId, LocalDate data, String tipoPagamento, String direcao,
                         int page, int size) {
        boolean filtraData = data != null;
        boolean filtraTipo = tipoPagamento != null;
        boolean filtraDirecao = direcao != null;
        LocalDate dia = filtraData ? data : LocalDate.EPOCH;
        String tipo = filtraTipo ? tipoPagamento : "";
        String direcaoFiltro = filtraDirecao ? direcao : "";
        List<LinhaConsulta> linhas = repositorio
                .listar(comercianteId, filtraData, dia, filtraTipo, tipo, filtraDirecao, direcaoFiltro, page, size)
                .stream()
                .map(LancamentoService::linha)
                .toList();
        long total = repositorio.contar(
                comercianteId, filtraData, dia, filtraTipo, tipo, filtraDirecao, direcaoFiltro);
        return new Pagina(linhas, total);
    }

    @Transactional(readOnly = true)
    public byte[] abrirComprovante(UUID comercianteId, UUID id) {
        byte[] comprovante = repositorio.findByIdAndComercianteId(id, comercianteId)
                .map(LancamentoDTO::getComprovante)
                .orElse(null);
        if (comprovante == null || comprovante.length == 0) {
            throw new RecusaLancamentoException("comprovante ausente");
        }
        return comprovante;
    }

    private static LinhaConsulta linha(Linha linha) {
        return new LinhaConsulta(
                linha.id(),
                linha.data(),
                linha.valor().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                linha.direcao(),
                linha.tipoPagamento(),
                linha.descricao(),
                linha.hasComprovante());
    }

    public record Pagina(List<LinhaConsulta> linhas, long total) {
    }

    public record LinhaConsulta(UUID id, LocalDate data, String valor, String direcao,
                                String tipoPagamento, String descricao, boolean hasComprovante) {
    }
}
