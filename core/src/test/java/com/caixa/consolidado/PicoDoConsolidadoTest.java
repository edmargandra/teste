package com.caixa.consolidado;

import com.caixa.CaixaApplication;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CaixaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PicoDoConsolidadoTest {

    private static final String DATA = "2026-11-04";
    private static final String TIPO = "Pix";
    private static final String DATA_DO_MEIO = "2026-11-20";
    private static final String TIPO_DO_MEIO = "boleto";
    private static final String BOUNDARY = "----caixa";
    private static final UUID comercianteSemeado = UUID.randomUUID();
    private static boolean semeado;

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    static {
        postgres.start();
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void garantirLinha() {
        if (semeado) {
            return;
        }
        semear();
        semeado = true;
    }

    @Test
    void picoDeSaldoECaixaNoMeio() throws Exception {
        assertThat(saldoDaData(200, corpoSemOTipo())).isFalse();
        assertThat(saldoDaData(200, corpoSemUmTotal())).isFalse();
        assertThat(saldoDaData(200, corpoSemCreditos())).isFalse();
        assertThat(saldoDaData(200, corpoSemDebitos())).isFalse();
        assertThat(saldoDaData(200, corpoSemSaldoDaData())).isFalse();
        assertThat(saldoDaData(500, corpoCompleto())).isFalse();
        assertThat(saldoDaData(200, corpoCompleto())).isTrue();

        UUID comerciante = comercianteSemeado;
        HttpClient cliente = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/saldo-diario-consolidado?data=" + DATA))
                .timeout(Duration.ofSeconds(10))
                .header("X-Comerciante-Id", comerciante.toString())
                .GET()
                .build();

        ExecutorService lado = Executors.newSingleThreadExecutor(r -> {
            Thread fio = new Thread(r, "caixa-no-meio");
            fio.setDaemon(true);
            return fio;
        });
        long inicio = System.nanoTime();
        Future<Meio> meio = lado.submit(() -> operarNoMeio(inicio, comerciante, port));
        int[] porSegundo = new int[10];
        int fora = 0;
        List<CompletableFuture<HttpResponse<String>>> pedidos = new ArrayList<>(500);
        try {
            for (int segundo = 0; segundo < 10; segundo++) {
                long alvo = inicio + segundo * 1_000_000_000L;
                while (System.nanoTime() < alvo) {
                    LockSupport.parkNanos(alvo - System.nanoTime());
                }
                for (int i = 0; i < 50; i++) {
                    long marca = System.nanoTime();
                    pedidos.add(cliente.sendAsync(pedido, HttpResponse.BodyHandlers.ofString()));
                    int faixa = (int) ((marca - inicio) / 1_000_000_000L);
                    if (faixa >= 0 && faixa < 10) {
                        porSegundo[faixa]++;
                    } else {
                        fora++;
                    }
                }
            }

            Meio resultado = meio.get(30, TimeUnit.SECONDS);
            assertThat(resultado.quando() - inicio).isBetween(4_500_000_000L, 6_500_000_000L);
            assertThat(resultado.concluido() - inicio).isLessThanOrEqualTo(10_000_000_000L);
            assertThat(resultado.registro().statusCode()).isEqualTo(201);
            String id = JsonPath.read(resultado.registro().body(), "$.id");
            assertThat(resultado.consulta().statusCode()).isEqualTo(200);
            List<Map<String, Object>> linhas = JsonPath.read(resultado.consulta().body(), "$.linhas");
            assertThat(linhas).hasSize(1);
            assertThat(linhas.get(0).get("id")).isEqualTo(id);
            assertThat(linhas.get(0).get("data")).isEqualTo(DATA_DO_MEIO);
            assertThat(linhas.get(0).get("tipo_pagamento")).isEqualTo(TIPO_DO_MEIO);
            assertThat(linhas.get(0).get("valor")).isEqualTo("7.25");
            assertThat(linhas.get(0).get("direcao")).isEqualTo("credito");
            assertThat(linhas.get(0).get("descricao")).isEqualTo("venda no pico");

            assertThat(fora).isZero();
            assertThat(porSegundo).containsExactly(50, 50, 50, 50, 50, 50, 50, 50, 50, 50);
            assertThat(pedidos).hasSize(500);

            int perda = 0;
            String exemplo = "";
            for (CompletableFuture<HttpResponse<String>> futuro : pedidos) {
                try {
                    HttpResponse<String> resposta = futuro.join();
                    if (!saldoDaData(resposta.statusCode(), resposta.body())) {
                        perda++;
                        if (exemplo.isEmpty()) {
                            exemplo = resposta.statusCode() + " " + cortar(resposta.body());
                        }
                    }
                } catch (CompletionException ex) {
                    perda++;
                    if (exemplo.isEmpty()) {
                        exemplo = String.valueOf(ex.getCause());
                    }
                }
            }
            assertThat(perda * 100L)
                    .as("perda %d de %d; exemplo %s", perda, pedidos.size(), exemplo)
                    .isLessThanOrEqualTo(pedidos.size() * 5L);
        } finally {
            lado.shutdownNow();
        }
    }

    private static Meio operarNoMeio(long inicio, UUID comerciante, int porta) throws Exception {
        long alvo = inicio + 5_000_000_000L;
        while (System.nanoTime() < alvo) {
            LockSupport.parkNanos(alvo - System.nanoTime());
        }
        long quando = System.nanoTime();
        HttpClient cliente = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpResponse<String> registro = cliente.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + porta + "/lancamentos"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("X-Comerciante-Id", comerciante.toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpoDoMeio()))
                .build(), HttpResponse.BodyHandlers.ofString());
        String consulta = "data=" + URLEncoder.encode(DATA_DO_MEIO, StandardCharsets.UTF_8)
                + "&tipo_pagamento=" + URLEncoder.encode(TIPO_DO_MEIO, StandardCharsets.UTF_8);
        HttpResponse<String> lista = cliente.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + porta + "/lancamentos?" + consulta))
                .timeout(Duration.ofSeconds(10))
                .header("X-Comerciante-Id", comerciante.toString())
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Meio(quando, System.nanoTime(), registro, lista);
    }

    private static boolean saldoDaData(int status, String corpo) {
        if (status != 200 || corpo == null) {
            return false;
        }
        try {
            List<Map<String, Object>> grupos = JsonPath.read(corpo, "$.grupos");
            boolean temTipo = false;
            for (Map<String, Object> grupo : grupos) {
                if (!TIPO.equals(String.valueOf(grupo.get("tipo_pagamento")))) {
                    continue;
                }
                if (!"10.50".equals(texto(grupo.get("total de créditos")))) {
                    return false;
                }
                if (!"0.00".equals(texto(grupo.get("total de débitos")))) {
                    return false;
                }
                if (!"10.50".equals(texto(grupo.get("saldo do tipo")))) {
                    return false;
                }
                temTipo = true;
            }
            return temTipo && "10.50".equals(texto(JsonPath.read(corpo, "$['saldo da data']")));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String corpoCompleto() {
        return """
                {"grupos":[{"tipo_pagamento":"Pix","total de créditos":"10.50","total de débitos":"0.00","saldo do tipo":"10.50"}],"saldo da data":"10.50"}
                """;
    }

    private static String corpoSemOTipo() {
        return """
                {"grupos":[{"tipo_pagamento":"dinheiro","total de créditos":"1.00","total de débitos":"0.00","saldo do tipo":"1.00"}],"saldo da data":"1.00"}
                """;
    }

    private static String corpoSemUmTotal() {
        return """
                {"grupos":[{"tipo_pagamento":"Pix","total de créditos":"10.50","total de débitos":"0.00"}],"saldo da data":"10.50"}
                """;
    }

    private static String corpoSemCreditos() {
        return """
                {"grupos":[{"tipo_pagamento":"Pix","total de débitos":"0.00","saldo do tipo":"10.50"}],"saldo da data":"10.50"}
                """;
    }

    private static String corpoSemDebitos() {
        return """
                {"grupos":[{"tipo_pagamento":"Pix","total de créditos":"10.50","saldo do tipo":"10.50"}],"saldo da data":"10.50"}
                """;
    }

    private static String corpoSemSaldoDaData() {
        return """
                {"grupos":[{"tipo_pagamento":"Pix","total de créditos":"10.50","total de débitos":"0.00","saldo do tipo":"10.50"}]}
                """;
    }

    private static String texto(Object valor) {
        return valor == null ? "" : valor.toString();
    }

    private static String cortar(String corpo) {
        if (corpo == null) {
            return "";
        }
        return corpo.length() <= 300 ? corpo : corpo.substring(0, 300);
    }

    private static byte[] corpoDoMeio() {
        Map<String, String> campos = Map.of(
                "data", DATA_DO_MEIO,
                "valor", "7.25",
                "direcao", "credito",
                "tipo_pagamento", TIPO_DO_MEIO,
                "descricao", "venda no pico");
        StringBuilder texto = new StringBuilder();
        for (Map.Entry<String, String> campo : campos.entrySet()) {
            texto.append("--").append(BOUNDARY).append("\r\n");
            texto.append("Content-Disposition: form-data; name=\"").append(campo.getKey()).append("\"\r\n");
            texto.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
            texto.append(campo.getValue()).append("\r\n");
        }
        texto.append("--").append(BOUNDARY).append("--\r\n");
        return texto.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void semear() {
        try (Connection conexao = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement comando = conexao.prepareStatement("""
                     INSERT INTO lancamento
                         (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            comando.setObject(1, UUID.randomUUID());
            comando.setObject(2, comercianteSemeado);
            comando.setDate(3, Date.valueOf(DATA));
            comando.setBigDecimal(4, new BigDecimal("10.50"));
            comando.setString(5, "credito");
            comando.setString(6, TIPO);
            comando.setString(7, "lancamento da data do pico");
            comando.executeUpdate();
            comando.setObject(1, UUID.randomUUID());
            comando.setObject(2, UUID.randomUUID());
            comando.setDate(3, Date.valueOf(DATA));
            comando.setBigDecimal(4, new BigDecimal("99.00"));
            comando.setString(5, "credito");
            comando.setString(6, TIPO);
            comando.setString(7, "outro caixa na mesma data");
            comando.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("nao foi possivel simular as linhas ja commitadas", ex);
        }
    }

    private record Meio(long quando, long concluido, HttpResponse<String> registro, HttpResponse<String> consulta) {
    }
}
