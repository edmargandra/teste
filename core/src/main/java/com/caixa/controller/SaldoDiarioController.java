package com.caixa.controller;

import com.caixa.adapter.response.GrupoSaldoResponse;
import com.caixa.adapter.response.SaldoDiarioResponse;
import com.caixa.exception.RecusaSaldoException;
import com.caixa.adapter.mapper.SaldoRestMapper;
import com.caixa.persistence.dto.GrupoSaldoDTO;
import com.caixa.service.SaldoDiarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
public class SaldoDiarioController {

    private static final Logger SINAL = LoggerFactory.getLogger(SaldoDiarioController.class);
    private static final Pattern DATA = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final SaldoDiarioService saldoDiarioService;

    public SaldoDiarioController(SaldoDiarioService saldoDiarioService) {
        this.saldoDiarioService = saldoDiarioService;
    }

    @GetMapping(path = "/saldo-diario-consolidado", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SaldoDiarioResponse> saldo(
            @RequestHeader(value = "X-Comerciante-Id", required = false) String comercianteId,
            @RequestParam(value = "data", required = false) String data) {
        try {
            List<GrupoSaldoDTO> totais = saldoDiarioService.porTipo(comerciante(comercianteId), data(data));
            List<GrupoSaldoResponse> grupos = new ArrayList<>();
            BigDecimal saldoDaData = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
            for (GrupoSaldoDTO tipo : totais) {
                BigDecimal saldoDoTipo = tipo.creditos().subtract(tipo.debitos());
                saldoDaData = saldoDaData.add(saldoDoTipo);
                grupos.add(SaldoRestMapper.grupo(tipo, saldoDoTipo));
            }
            SaldoDiarioResponse corpo = new SaldoDiarioResponse(grupos, SaldoRestMapper.texto(saldoDaData));
            ResponseEntity<SaldoDiarioResponse> resposta = ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corpo);
            if (corpo.grupos() != null && corpo.saldoDaData() != null) {
                SINAL.info("sinal saldo devolvido");
            } else {
                SINAL.info("sinal saldo perda");
            }
            return resposta;
        } catch (RecusaSaldoException ex) {
            SINAL.info("sinal saldo perda");
            throw ex;
        }
    }

    private static UUID comerciante(String header) {
        if (header == null || header.isBlank()) {
            throw new RecusaSaldoException("X-Comerciante-Id ausente ou invalido");
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException ex) {
            throw new RecusaSaldoException("X-Comerciante-Id ausente ou invalido");
        }
    }

    private static LocalDate data(String informado) {
        if (informado == null || !DATA.matcher(informado).matches()) {
            throw new RecusaSaldoException("data invalida");
        }
        try {
            return LocalDate.parse(informado);
        } catch (DateTimeParseException ex) {
            throw new RecusaSaldoException("data invalida");
        }
    }
}
