package com.caixa.service;

import com.caixa.application.SaldoRepositoryPort;
import com.caixa.persistence.dto.GrupoSaldoDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class SaldoDiarioService {

    static final List<String> ORDEM = List.of(
            "Pix",
            "cartão de crédito",
            "cartão de débito",
            "dinheiro",
            "boleto",
            "depósito");

    private final SaldoRepositoryPort repositorio;

    public SaldoDiarioService(SaldoRepositoryPort repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional(readOnly = true)
    public List<GrupoSaldoDTO> porTipo(UUID comercianteId, LocalDate data) {
        List<GrupoSaldoDTO> grupos = new ArrayList<>(repositorio.porTipo(comercianteId, data));
        grupos.sort(Comparator.comparingInt(SaldoDiarioService::indice));
        return grupos;
    }

    private static int indice(GrupoSaldoDTO grupo) {
        int ordem = ORDEM.indexOf(grupo.tipoPagamento());
        return ordem < 0 ? ORDEM.size() : ordem;
    }
}
