package com.caixa.lancamento;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsolidadoForaTest {

    private static final String BOUNDARY = "----caixa";

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    static {
        postgres.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int port;

    @Test
    void registroESaldoRespondemNoMesmoProcesso() throws Exception {
        UUID comerciante = UUID.randomUUID();
        Map<String, String> campos = validos();

        assertThat(context.getBeansOfType(RestTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(RestClient.class)).isEmpty();
        assertThatThrownBy(() -> Class.forName("com.caixa.consolidado.ConsolidadoApplication"))
                .isInstanceOf(ClassNotFoundException.class);

        HttpResponse<String> registro = enviar(comerciante.toString(), campos);
        assertThat(registro.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(registro.body(), "$.id"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM lancamento WHERE id = ? AND comerciante_id = ? AND data = ? AND tipo_pagamento = ?",
                Integer.class, id, comerciante, Date.valueOf(campos.get("data")), campos.get("tipo_pagamento")))
                .isEqualTo(1);

        HttpResponse<String> consulta = consultar(
                comerciante.toString(), campos.get("data"), campos.get("tipo_pagamento"));
        assertThat(consulta.statusCode()).isEqualTo(200);
        List<Map<String, Object>> linhas = JsonPath.read(consulta.body(), "$.linhas");
        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0).get("id")).isEqualTo(id.toString());
        assertThat(linhas.get(0).get("data")).isEqualTo(campos.get("data"));
        assertThat(linhas.get(0).get("tipo_pagamento")).isEqualTo(campos.get("tipo_pagamento"));

        HttpResponse<String> saldo = pedirSaldo(comerciante.toString(), campos.get("data"));
        assertThat(saldo.statusCode()).isEqualTo(200);
        assertThat(saldo.headers().firstValue("content-type").orElse("")).startsWith("application/json");
        assertThat(JsonPath.read(saldo.body(), "$.grupos[0].tipo_pagamento").toString()).isEqualTo("Pix");
        assertThat(JsonPath.read(saldo.body(), "$.grupos[0]['total de créditos']").toString()).isEqualTo("10.50");
        assertThat(JsonPath.read(saldo.body(), "$.grupos[0]['total de débitos']").toString()).isEqualTo("0.00");
        assertThat(JsonPath.read(saldo.body(), "$.grupos[0]['saldo do tipo']").toString()).isEqualTo("10.50");
        assertThat(JsonPath.read(saldo.body(), "$['saldo da data']").toString()).isEqualTo("10.50");
    }

    private static Map<String, String> validos() {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("data", "2026-11-04");
        campos.put("valor", "10.50");
        campos.put("direcao", "credito");
        campos.put("tipo_pagamento", "Pix");
        campos.put("descricao", "venda do dia");
        return campos;
    }

    private HttpResponse<String> enviar(String comerciante, Map<String, String> campos) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/lancamentos"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("X-Comerciante-Id", comerciante)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo(campos)))
                .build();
        return HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> consultar(String comerciante, String data, String tipoPagamento) throws Exception {
        String consulta = "data=" + URLEncoder.encode(data, StandardCharsets.UTF_8)
                + "&tipo_pagamento=" + URLEncoder.encode(tipoPagamento, StandardCharsets.UTF_8);
        HttpRequest pedido = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/lancamentos?" + consulta))
                .timeout(Duration.ofSeconds(10))
                .header("X-Comerciante-Id", comerciante)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> pedirSaldo(String comerciante, String data) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/saldo-diario-consolidado?data=" + data))
                .timeout(Duration.ofSeconds(10))
                .header("X-Comerciante-Id", comerciante)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] corpo(Map<String, String> campos) {
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
}
