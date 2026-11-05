package com.caixa.consolidado;

import com.caixa.CaixaApplication;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CaixaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SinalDeSaldoTest {

    private static final String DESCRICAO = "DESCRICAO-FORA-DO-SINAL";

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    static {
        postgres.start();
    }

    @LocalServerPort
    private int port;

    @Test
    void diaComLancamentoSinalizaSaldoDevolvido(CapturedOutput saida) throws Exception {
        UUID comerciante = UUID.randomUUID();
        inserir(comerciante, "2026-11-04", "10.50", "credito", "Pix");

        HttpResponse<String> resposta = pedir(comerciante.toString(), "2026-11-04");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(resposta.body(), "$.grupos")).hasSize(1);
        assertThat(JsonPath.read(resposta.body(), "$['saldo da data']").toString()).isEqualTo("10.50");
        sinalDevolvido(saida);
    }

    @Test
    void diaVazioSinalizaSaldoENaoPerda(CapturedOutput saida) throws Exception {
        HttpResponse<String> resposta = pedir(UUID.randomUUID().toString(), "2026-11-08");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(resposta.body(), "$.grupos")).isEmpty();
        assertThat(JsonPath.read(resposta.body(), "$['saldo da data']").toString()).isEqualTo("0.00");
        sinalDevolvido(saida);
    }

    @Test
    void respostaSemSaldoDaDataSinalizaPerda(CapturedOutput saida) throws Exception {
        HttpResponse<String> semComerciante = pedir(null, "2026-11-04");
        HttpResponse<String> dataInvalida = pedir(UUID.randomUUID().toString(), "04/11/2026");

        for (HttpResponse<String> resposta : List.of(semComerciante, dataInvalida)) {
            assertThat(resposta.statusCode()).isEqualTo(400);
            assertThat(tipo(resposta)).startsWith("application/problem+json");
            assertThat(resposta.body()).doesNotContain("\"grupos\"").doesNotContain("saldo da data");
        }
        assertThat(saida).contains("sinal saldo perda");
        assertThat(ocorrencias(saida, "sinal saldo perda")).isEqualTo(2);
        assertThat(saida).doesNotContain("sinal saldo devolvido");
        assertThat(linhasDeSinal(saida)).allSatisfy(linha -> assertThat(linha).doesNotContain(DESCRICAO));
    }

    private static void sinalDevolvido(CapturedOutput saida) {
        assertThat(saida).contains("sinal saldo devolvido");
        assertThat(saida).doesNotContain("sinal saldo perda");
        assertThat(linhasDeSinal(saida)).isNotEmpty().allSatisfy(linha -> assertThat(linha).doesNotContain(DESCRICAO));
    }

    private static List<String> linhasDeSinal(CapturedOutput saida) {
        return saida.getAll().lines().filter(linha -> linha.contains("sinal ")).toList();
    }

    private static int ocorrencias(CapturedOutput saida, String frase) {
        String texto = saida.getAll();
        int total = 0;
        int indice = 0;
        while ((indice = texto.indexOf(frase, indice)) >= 0) {
            total++;
            indice += frase.length();
        }
        return total;
    }

    private HttpResponse<String> pedir(String comerciante, String data) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/saldo-diario-consolidado?data=" + data))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (comerciante != null) {
            pedido.header("X-Comerciante-Id", comerciante);
        }
        return HttpClient.newHttpClient().send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String tipo(HttpResponse<String> resposta) {
        return resposta.headers().firstValue("content-type").orElse("");
    }

    private static void inserir(UUID comerciante, String data, String valor, String direcao, String tipo) {
        try (Connection conexao = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement comando = conexao.prepareStatement("""
                     INSERT INTO lancamento
                         (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            comando.setObject(1, UUID.randomUUID());
            comando.setObject(2, comerciante);
            comando.setDate(3, Date.valueOf(data));
            comando.setBigDecimal(4, new BigDecimal(valor));
            comando.setString(5, direcao);
            comando.setString(6, tipo);
            comando.setString(7, DESCRICAO);
            comando.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("nao foi possivel simular as linhas ja commitadas", ex);
        }
    }
}
