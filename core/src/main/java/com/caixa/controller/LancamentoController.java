package com.caixa.controller;

import com.caixa.adapter.response.LinhaLancamentoResponse;
import com.caixa.persistence.dto.LancamentoAceitoDTO;
import com.caixa.persistence.dto.PaginaLancamentoDTO;
import com.caixa.adapter.mapper.LancamentoRestMapper;
import com.caixa.service.LancamentoService;
import com.caixa.exception.RecusaLancamentoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
public class LancamentoController {

    private static final Logger SINAL = LoggerFactory.getLogger(LancamentoController.class);
    private static final int TAMANHO_MAXIMO = 20;
    private static final Pattern VALOR = Pattern.compile("^(0|[1-9]\\d{0,16})\\.\\d{2}$");
    private static final Pattern DATA = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern PAGINA = Pattern.compile("\\d+");
    private static final Pattern TAMANHO = Pattern.compile("[1-9]\\d*");
    private static final Set<String> TIPOS = Set.of(
            "Pix",
            "cartão de crédito",
            "cartão de débito",
            "dinheiro",
            "boleto",
            "depósito");

    private final LancamentoService lancamentoService;

    public LancamentoController(LancamentoService lancamentoService) {
        this.lancamentoService = lancamentoService;
    }

    @PostMapping(path = "/lancamentos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LancamentoAceitoDTO> registrar(
            @RequestHeader(value = "X-Comerciante-Id", required = false) String comercianteId,
            @RequestParam(value = "data", required = false) String dataInformada,
            @RequestParam(value = "valor", required = false) String valorInformado,
            @RequestParam(value = "direcao", required = false) String direcaoInformada,
            @RequestParam(value = "tipo_pagamento", required = false) String tipoPagamento,
            @RequestParam(value = "descricao", required = false) String descricaoInformada,
            @RequestParam(value = "comprovante", required = false) MultipartFile comprovanteInformado) {
        try {
            UUID comerciante = comerciante(comercianteId);
            LocalDate dia = data(dataInformada);
            BigDecimal valor = LancamentoController.valor(valorInformado);
            String direcao = LancamentoController.direcao(direcaoInformada);
            String tipo = tipo(tipoPagamento);
            String descricao = LancamentoController.descricao(descricaoInformada);
            byte[] comprovante = LancamentoController.comprovante(comprovanteInformado);

            UUID id = UUID.randomUUID();
            lancamentoService.gravar(id, comerciante, dia, valor, direcao, tipo, descricao, comprovante);
            ResponseEntity<LancamentoAceitoDTO> aceito = ResponseEntity.status(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LancamentoAceitoDTO(id));
            SINAL.info("sinal registro aceito");
            return aceito;
        } catch (RecusaLancamentoException ex) {
            SINAL.info("sinal registro recusado");
            throw ex;
        }
    }

    @PutMapping(path = "/lancamentos/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LancamentoAceitoDTO> corrigir(
            @RequestHeader(value = "X-Comerciante-Id", required = false) String comercianteId,
            @PathVariable("id") String id,
            @RequestParam(value = "data", required = false) String dataInformada,
            @RequestParam(value = "valor", required = false) String valorInformado,
            @RequestParam(value = "direcao", required = false) String direcaoInformada,
            @RequestParam(value = "tipo_pagamento", required = false) String tipoPagamento,
            @RequestParam(value = "descricao", required = false) String descricaoInformada,
            @RequestParam(value = "comprovante", required = false) MultipartFile comprovanteInformado) {
        try {
            UUID comerciante = comerciante(comercianteId);
            LocalDate dia = data(dataInformada);
            BigDecimal valor = LancamentoController.valor(valorInformado);
            String direcao = LancamentoController.direcao(direcaoInformada);
            String tipo = tipo(tipoPagamento);
            String descricao = LancamentoController.descricao(descricaoInformada);
            byte[] comprovante = LancamentoController.comprovante(comprovanteInformado);
            UUID lancamentoId = lancamento(id);

            lancamentoService.corrigir(lancamentoId, comerciante, dia, valor, direcao, tipo, descricao, comprovante);
            ResponseEntity<LancamentoAceitoDTO> aceito = ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LancamentoAceitoDTO(lancamentoId));
            SINAL.info("sinal correcao aceita");
            return aceito;
        } catch (RecusaLancamentoException ex) {
            SINAL.info("sinal correcao recusada");
            throw ex;
        }
    }

    @GetMapping(path = "/lancamentos", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaginaLancamentoDTO> listar(
            @RequestHeader(value = "X-Comerciante-Id", required = false) String comercianteId,
            @RequestParam(value = "data", required = false) String data,
            @RequestParam(value = "tipo_pagamento", required = false) String tipoPagamento,
            @RequestParam(value = "direcao", required = false) String direcao,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "size", required = false) String size) {
        try {
            LancamentoService.Pagina pagina = lancamentoService.listar(
                    comerciante(comercianteId),
                    dataOpcional(data),
                    tipoOpcional(tipoPagamento),
                    direcaoOpcional(direcao),
                    pagina(page),
                    tamanho(size));
            List<LinhaLancamentoResponse> linhas = pagina.linhas().stream()
                    .map(LancamentoRestMapper::linha)
                    .toList();
            ResponseEntity<PaginaLancamentoDTO> resposta = ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PaginaLancamentoDTO(linhas, pagina.total()));
            if (linhas.isEmpty()) {
                SINAL.info("sinal consulta vazia");
            } else {
                SINAL.info("sinal consulta devolveu");
            }
            return resposta;
        } catch (RecusaLancamentoException ex) {
            SINAL.info("sinal consulta recusada");
            throw ex;
        }
    }

    @GetMapping(path = "/lancamentos/{id}/comprovante", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> comprovante(
            @RequestHeader(value = "X-Comerciante-Id", required = false) String comercianteId,
            @PathVariable("id") String id) {
        byte[] comprovante = lancamentoService.abrirComprovante(comerciante(comercianteId), lancamento(id));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(comprovante);
    }

    private static UUID lancamento(String informado) {
        if (informado == null || informado.isBlank()) {
            throw new RecusaLancamentoException("lancamento ausente");
        }
        try {
            return UUID.fromString(informado);
        } catch (IllegalArgumentException ex) {
            throw new RecusaLancamentoException("lancamento ausente");
        }
    }

    private static UUID comerciante(String header) {
        if (header == null || header.isBlank()) {
            throw new RecusaLancamentoException("X-Comerciante-Id ausente ou invalido");
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException ex) {
            throw new RecusaLancamentoException("X-Comerciante-Id ausente ou invalido");
        }
    }

    private static LocalDate dataOpcional(String informado) {
        if (informado == null || informado.isBlank()) {
            return null;
        }
        return data(informado);
    }

    private static String tipoOpcional(String informado) {
        if (informado == null || informado.isBlank()) {
            return null;
        }
        return tipo(informado);
    }

    private static String direcaoOpcional(String informado) {
        if (informado == null || informado.isBlank()) {
            return null;
        }
        return direcao(informado);
    }

    private static int pagina(String informado) {
        if (informado == null || informado.isBlank()) {
            return 0;
        }
        if (!PAGINA.matcher(informado).matches()) {
            throw new RecusaLancamentoException("page invalida");
        }
        try {
            return Integer.parseInt(informado);
        } catch (NumberFormatException ex) {
            throw new RecusaLancamentoException("page invalida");
        }
    }

    private static int tamanho(String informado) {
        if (informado == null || informado.isBlank()) {
            return TAMANHO_MAXIMO;
        }
        if (!TAMANHO.matcher(informado).matches()) {
            throw new RecusaLancamentoException("size invalido");
        }
        try {
            return Math.min(Integer.parseInt(informado), TAMANHO_MAXIMO);
        } catch (NumberFormatException ex) {
            throw new RecusaLancamentoException("size invalido");
        }
    }

    private static LocalDate data(String informado) {
        if (informado == null || !DATA.matcher(informado).matches()) {
            throw new RecusaLancamentoException("data invalida");
        }
        try {
            return LocalDate.parse(informado);
        } catch (DateTimeParseException ex) {
            throw new RecusaLancamentoException("data invalida");
        }
    }

    private static BigDecimal valor(String informado) {
        if (informado == null || !VALOR.matcher(informado).matches()) {
            throw new RecusaLancamentoException("valor invalido");
        }
        BigDecimal valor = new BigDecimal(informado);
        if (valor.signum() <= 0) {
            throw new RecusaLancamentoException("valor invalido");
        }
        return valor;
    }

    private static String direcao(String informado) {
        if (!"debito".equals(informado) && !"credito".equals(informado)) {
            throw new RecusaLancamentoException("direcao invalida");
        }
        return informado;
    }

    private static String tipo(String informado) {
        if (informado == null || !TIPOS.contains(informado)) {
            throw new RecusaLancamentoException("tipo_pagamento invalido");
        }
        return informado;
    }

    private static String descricao(String informado) {
        if (informado == null || informado.isBlank()) {
            throw new RecusaLancamentoException("descricao ausente");
        }
        return informado;
    }

    private static byte[] comprovante(MultipartFile comprovante) {
        if (comprovante == null || comprovante.isEmpty()) {
            return null;
        }
        try {
            return comprovante.getBytes();
        } catch (IOException ex) {
            throw new RecusaLancamentoException("comprovante ilegivel");
        }
    }
}
