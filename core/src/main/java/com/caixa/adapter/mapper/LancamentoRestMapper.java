package com.caixa.adapter.mapper;

import com.caixa.adapter.response.LinhaLancamentoResponse;
import com.caixa.service.LancamentoService;

public final class LancamentoRestMapper {

    private LancamentoRestMapper() {
    }

    public static LinhaLancamentoResponse linha(LancamentoService.LinhaConsulta linha) {
        return new LinhaLancamentoResponse(
                linha.id().toString(),
                linha.data().toString(),
                linha.valor(),
                linha.direcao(),
                linha.tipoPagamento(),
                linha.descricao(),
                linha.hasComprovante());
    }
}
