package com.caixa.lancamento;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsDaTelaTest {

    private static final String ORIGEM = "http://127.0.0.1:4200";
    private static final String ORIGEM_LOCAL = "http://localhost:4200";

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    static {
        postgres.start();
    }

    @LocalServerPort
    private int port;

    @Test
    void aOrigemDaTelaPodeChamarLancamentoESaldo() throws Exception {
        HttpClient cliente = HttpClient.newHttpClient();

        HttpResponse<String> preflight = cliente.send(opcoes("/lancamentos", ORIGEM, "POST"), corpo());
        assertThat(preflight.statusCode()).isBetween(200, 299);
        assertThat(preflight.headers().firstValue("access-control-allow-origin")).hasValue(ORIGEM);
        assertThat(preflight.headers().firstValue("access-control-allow-headers"))
                .hasValueSatisfying(valor -> assertThat(valor.toLowerCase()).contains("x-comerciante-id"));
        assertThat(preflight.headers().firstValue("access-control-allow-methods"))
                .hasValueSatisfying(valor -> assertThat(valor.toUpperCase()).contains("POST"));

        HttpResponse<String> alterar = cliente.send(
                opcoes("/lancamentos/11111111-1111-4111-8111-111111111111", ORIGEM, "PUT"),
                corpo());
        assertThat(alterar.statusCode()).isBetween(200, 299);
        assertThat(alterar.headers().firstValue("access-control-allow-origin")).hasValue(ORIGEM);
        assertThat(alterar.headers().firstValue("access-control-allow-headers"))
                .hasValueSatisfying(valor -> assertThat(valor.toLowerCase()).contains("x-comerciante-id"));
        assertThat(alterar.headers().firstValue("access-control-allow-methods"))
                .hasValueSatisfying(valor -> assertThat(valor.toUpperCase()).contains("PUT"));

        HttpResponse<String> saldo = cliente.send(opcoes("/saldo-diario-consolidado", ORIGEM_LOCAL, "GET"), corpo());
        assertThat(saldo.statusCode()).isBetween(200, 299);
        assertThat(saldo.headers().firstValue("access-control-allow-origin")).hasValue(ORIGEM_LOCAL);
        assertThat(saldo.headers().firstValue("access-control-allow-methods"))
                .hasValueSatisfying(valor -> assertThat(valor.toUpperCase()).contains("GET"));

        HttpResponse<String> consulta = cliente.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/lancamentos?page=0&size=20"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Origin", ORIGEM)
                        .header("X-Comerciante-Id", "11111111-1111-4111-8111-111111111111")
                        .GET()
                        .build(),
                corpo());
        assertThat(consulta.headers().firstValue("access-control-allow-origin")).hasValue(ORIGEM);

        HttpResponse<String> estranha = cliente.send(opcoes("/lancamentos", "http://127.0.0.1:4300", "POST"), corpo());
        assertThat(estranha.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @Test
    void aApiNaoServeMaisATela() throws Exception {
        HttpClient cliente = HttpClient.newHttpClient();
        for (String caminho : new String[]{"/", "/tela.js", "/tela.css"}) {
            HttpResponse<String> resposta = cliente.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + caminho))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    corpo());
            assertThat(resposta.statusCode()).isEqualTo(404);
        }
    }

    private HttpRequest opcoes(String caminho, String origem, String metodo) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + caminho))
                .timeout(Duration.ofSeconds(5))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origem)
                .header("Access-Control-Request-Method", metodo)
                .header("Access-Control-Request-Headers", "X-Comerciante-Id")
                .build();
    }

    private static HttpResponse.BodyHandler<String> corpo() {
        return HttpResponse.BodyHandlers.ofString();
    }
}
