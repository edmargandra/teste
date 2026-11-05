package com.caixa.adapter.mapper;

import com.caixa.adapter.response.GrupoSaldoResponse;
import com.caixa.persistence.dto.GrupoSaldoDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class SaldoRestMapper {

    private SaldoRestMapper() {
    }

    public static GrupoSaldoResponse grupo(GrupoSaldoDTO grupo, BigDecimal saldoDoTipo) {
        return new GrupoSaldoResponse(
                grupo.tipoPagamento(),
                texto(grupo.creditos()),
                texto(grupo.debitos()),
                texto(saldoDoTipo));
    }

    public static String texto(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
