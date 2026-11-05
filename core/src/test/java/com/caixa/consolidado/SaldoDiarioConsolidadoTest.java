package com.caixa.consolidado;

import com.caixa.CaixaApplication;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CaixaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SaldoDiarioConsolidadoTest {

    private static final String CAIXA_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CAIXA_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static boolean semeado;

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    static {
        RuntimeException ultima = null;
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            try {
                postgres.start();
                ultima = null;
                break;
            } catch (RuntimeException ex) {
                ultima = ex;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrompido) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        if (ultima != null) {
            throw ultima;
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    @Test
    void diaComDoisTipos() throws Exception {
        int antes = total();
        HttpResponse<String> resposta = pedir(CAIXA_A, "2026-11-04");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(tipo(resposta)).startsWith("application/json");
        assertThat(JsonPath.<List<?>>read(resposta.body(), "$.grupos")).hasSize(2);
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0].tipo_pagamento").toString()).isEqualTo("Pix");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['total de créditos']").toString()).isEqualTo("10.50");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['total de débitos']").toString()).isEqualTo("2.00");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['saldo do tipo']").toString()).isEqualTo("8.50");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[1].tipo_pagamento").toString()).isEqualTo("dinheiro");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[1]['total de créditos']").toString()).isEqualTo("1.00");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[1]['total de débitos']").toString()).isEqualTo("0.00");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[1]['saldo do tipo']").toString()).isEqualTo("1.00");
        assertThat(JsonPath.read(resposta.body(), "$['saldo da data']").toString()).isEqualTo("9.50");
        assertThat(total()).isEqualTo(antes);
    }

    @Test
    void saldoDeTipoNegativo() throws Exception {
        HttpResponse<String> resposta = pedir(CAIXA_A, "2026-11-05");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(resposta.body(), "$.grupos")).hasSize(1);
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['total de créditos']").toString()).isEqualTo("2.00");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['total de débitos']").toString()).isEqualTo("3.00");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['saldo do tipo']").toString()).isEqualTo("-1.00");
        assertThat(JsonPath.read(resposta.body(), "$['saldo da data']").toString()).isEqualTo("-1.00");
    }

    @Test
    void diaSemLancamento() throws Exception {
        HttpResponse<String> resposta = pedir(CAIXA_A, "2026-11-06");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(tipo(resposta)).startsWith("application/json");
        assertThat(JsonPath.<List<?>>read(resposta.body(), "$.grupos")).isEmpty();
        assertThat(JsonPath.read(resposta.body(), "$['saldo da data']").toString()).isEqualTo("0.00");
    }

    @Test
    void outroCaixaNaoEntraNaConta() throws Exception {
        HttpResponse<String> resposta = pedir(CAIXA_B, "2026-11-04");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(resposta.body(), "$.grupos")).isEmpty();
        assertThat(JsonPath.read(resposta.body(), "$['saldo da data']").toString()).isEqualTo("0.00");
        assertThat(resposta.body()).doesNotContain("10.50").doesNotContain("9.50");
    }

    @Test
    void ordemDosGruposSegueAd8() throws Exception {
        HttpResponse<String> resposta = pedir(CAIXA_A, "2026-12-01");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(resposta.body(), "$.grupos[*].tipo_pagamento"))
                .containsExactly(
                        "Pix",
                        "cartão de crédito",
                        "cartão de débito",
                        "dinheiro",
                        "boleto",
                        "depósito");
        assertThat(JsonPath.read(resposta.body(), "$['saldo da data']").toString()).isEqualTo("6.00");
    }

    @Test
    void somaVariasLinhasDoMesmoTipo() throws Exception {
        HttpResponse<String> resposta = pedir(CAIXA_A, "2026-11-07");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<?>>read(resposta.body(), "$.grupos")).hasSize(1);
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0].tipo_pagamento").toString()).isEqualTo("Pix");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['total de créditos']").toString()).isEqualTo("10.50");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['total de débitos']").toString()).isEqualTo("2.50");
        assertThat(JsonPath.read(resposta.body(), "$.grupos[0]['saldo do tipo']").toString()).isEqualTo("8.00");
        assertThat(JsonPath.read(resposta.body(), "$['saldo da data']").toString()).isEqualTo("8.00");
    }

    @Test
    void identidadeOuDataInvalidasNaoDevolvemSaldo() throws Exception {
        recusa(null, "2026-11-04");
        recusa("nao-e-uuid", "2026-11-04");
        recusa(CAIXA_A, null);
        recusa(CAIXA_A, "04/11/2026");
        recusa(CAIXA_A, "2026-02-31");
    }

    private void recusa(String comerciante, String data) throws Exception {
        HttpResponse<String> resposta = pedir(comerciante, data);
        assertThat(resposta.statusCode()).isEqualTo(400);
        assertThat(tipo(resposta)).startsWith("application/problem+json");
        assertThat(resposta.body()).doesNotContain("\"grupos\"").doesNotContain("saldo da data");
    }

    private int total() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM lancamento", Integer.class);
    }

    private HttpResponse<String> pedir(String comerciante, String data) throws Exception {
        String consulta = data == null ? "" : "?data=" + data;
        HttpRequest.Builder pedido = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/saldo-diario-consolidado" + consulta))
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

    @BeforeEach
    void linhasJaCommitadasPeloControle() {
        if (semeado) {
            return;
        }
        try (Connection conexao = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement comando = conexao.createStatement()) {
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa01", CAIXA_A, "2026-11-04", "10.50", "credito", "Pix");
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa02", CAIXA_A, "2026-11-04", "2.00", "debito", "Pix");
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa03", CAIXA_A, "2026-11-04", "1.00", "credito", "dinheiro");
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa04", CAIXA_A, "2026-11-05", "2.00", "credito", "Pix");
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa05", CAIXA_A, "2026-11-05", "3.00", "debito", "Pix");
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa12", CAIXA_A, "2026-11-07", "10.00", "credito", "Pix");
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa13", CAIXA_A, "2026-11-07", "0.50", "credito", "Pix");
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa14", CAIXA_A, "2026-11-07", "1.00", "debito", "Pix");
            inserir(comando, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa15", CAIXA_A, "2026-11-07", "1.50", "debito", "Pix");
            String[] tipos = {
                    "depósito", "boleto", "dinheiro", "cartão de débito", "cartão de crédito", "Pix"
            };
            int seq = 6;
            for (String tipo : tipos) {
                inserir(comando,
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa%02d".formatted(seq),
                        CAIXA_A, "2026-12-01", "1.00", "credito", tipo);
                seq++;
            }
            semeado = true;
        } catch (SQLException ex) {
            throw new IllegalStateException("nao foi possivel simular as linhas ja commitadas", ex);
        }
    }

    private static void inserir(Statement comando, String id, String caixa, String data, String valor,
                                String direcao, String tipo) throws SQLException {
        comando.execute("""
                INSERT INTO lancamento (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao)
                VALUES ('%s', '%s', DATE '%s', %s, '%s', '%s', 'linha ja gravada pelo controle')
                """.formatted(id, caixa, data, valor, direcao, tipo));
    }
}
