package com.caixa.lancamento;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
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
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SinaisTest {

    private static final String BOUNDARY = "----caixa";
    private static final String DESCRICAO = "DESCRICAO-FORA-DO-SINAL";
    private static final byte[] COMPROVANTE = "COMPROVANTE-FORA-DO-SINAL".getBytes(StandardCharsets.UTF_8);

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
    void registroAceitoSinalizaDepoisDo201SemDescricaoNemComprovante(CapturedOutput saida) throws Exception {
        UUID comerciante = UUID.randomUUID();
        HttpResponse<String> resposta = enviar(comerciante.toString(), validos(), COMPROVANTE);

        assertThat(resposta.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(resposta.body(), "$.id"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM lancamento WHERE id = ? AND comerciante_id = ?",
                Integer.class, id, comerciante)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT comprovante FROM lancamento WHERE id = ?", byte[].class, id))
                .containsExactly(COMPROVANTE);
        sinal(saida, "sinal registro aceito");
        assertThat(saida).doesNotContain("sinal registro recusado");
    }

    @Test
    void registroRecusadoSinalizaENaoEntra(CapturedOutput saida) throws Exception {
        int antes = total();

        HttpResponse<String> valorZero = enviar(UUID.randomUUID().toString(), campos("valor", "0.00"), COMPROVANTE);
        HttpResponse<String> tipoFora = enviar(UUID.randomUUID().toString(), campos("tipo_pagamento", "cheque"), COMPROVANTE);
        HttpResponse<String> semComerciante = enviar(null, validos(), COMPROVANTE);

        for (HttpResponse<String> resposta : List.of(valorZero, tipoFora, semComerciante)) {
            assertThat(resposta.statusCode()).isEqualTo(400);
            assertThat(tipo(resposta)).startsWith("application/problem+json");
        }
        assertThat(total()).isEqualTo(antes);
        sinal(saida, "sinal registro recusado");
        assertThat(ocorrencias(saida, "sinal registro recusado")).isEqualTo(3);
        assertThat(saida).doesNotContain("sinal registro aceito");
    }

    @Test
    void correcaoAceitaERecusadaSinalizamSemDescricaoNemComprovante(CapturedOutput saida) throws Exception {
        UUID comerciante = UUID.randomUUID();
        HttpResponse<String> criado = enviar(comerciante.toString(), validos(), COMPROVANTE);
        assertThat(criado.statusCode()).isEqualTo(201);
        String id = JsonPath.read(criado.body(), "$.id");

        HttpResponse<String> aceita = corrigir(comerciante.toString(), id, validos(), COMPROVANTE);
        assertThat(aceita.statusCode()).isEqualTo(200);
        HttpResponse<String> recusada = corrigir(comerciante.toString(), id, campos("valor", "0.00"), COMPROVANTE);
        assertThat(recusada.statusCode()).isEqualTo(400);

        sinal(saida, "sinal correcao aceita");
        sinal(saida, "sinal correcao recusada");
        assertThat(ocorrencias(saida, "sinal correcao aceita")).isEqualTo(1);
        assertThat(ocorrencias(saida, "sinal correcao recusada")).isEqualTo(1);
    }

    @Test
    void consultaComLinhaSinalizaDevolveuSemDescricaoNemComprovante(CapturedOutput saida) throws Exception {
        UUID comerciante = UUID.randomUUID();
        HttpResponse<String> registro = enviar(comerciante.toString(), validos(), COMPROVANTE);
        assertThat(registro.statusCode()).isEqualTo(201);

        HttpResponse<String> consulta = consultar(comerciante.toString(), "2026-11-04", "Pix");

        assertThat(consulta.statusCode()).isEqualTo(200);
        List<Map<String, Object>> linhas = JsonPath.read(consulta.body(), "$.linhas");
        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0).get("descricao")).isEqualTo(DESCRICAO);
        sinal(saida, "sinal consulta devolveu");
        assertThat(saida).doesNotContain("sinal consulta vazia").doesNotContain("sinal consulta recusada");
    }

    @Test
    void consultaVaziaSinalizaQueNaoDevolveu(CapturedOutput saida) throws Exception {
        HttpResponse<String> consulta = consultar(UUID.randomUUID().toString(), "2026-11-04", "Pix");

        assertThat(consulta.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(consulta.body(), "$.linhas")).isEmpty();
        sinal(saida, "sinal consulta vazia");
        assertThat(saida).doesNotContain("sinal consulta devolveu").doesNotContain("sinal consulta recusada");
    }

    @Test
    void consultaRecusadaNaoEDevolveu(CapturedOutput saida) throws Exception {
        HttpResponse<String> resposta = consultar(null, "2026-11-04", "cheque");

        assertThat(resposta.statusCode()).isEqualTo(400);
        assertThat(tipo(resposta)).startsWith("application/problem+json");
        sinal(saida, "sinal consulta recusada");
        assertThat(saida).doesNotContain("sinal consulta devolveu").doesNotContain("sinal consulta vazia");
    }

    @Test
    void comprovanteNaoEmiteSinalDeRegistroNemDeConsulta(CapturedOutput saida) throws Exception {
        UUID comerciante = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO lancamento
                    (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao, comprovante)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, comerciante, Date.valueOf("2026-11-04"), new BigDecimal("10.50"),
                "credito", "Pix", DESCRICAO, COMPROVANTE);

        HttpResponse<byte[]> resposta = abrir(comerciante.toString(), id.toString());

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body()).containsExactly(COMPROVANTE);
        assertThat(saida).doesNotContain("sinal registro").doesNotContain("sinal consulta");
        assertThat(linhasDeSinal(saida)).noneMatch(linha -> linha.contains(DESCRICAO) || linha.contains("COMPROVANTE-FORA-DO-SINAL"));
    }

    @Test
    void consolidadoParadoRegistroEConsultaCompletamESaldoFalha(CapturedOutput saida) throws Exception {
        assertThat(context.getBeansOfType(RestTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(RestClient.class)).isEmpty();
        assertThatThrownBy(() -> Class.forName("com.caixa.consolidado.ConsolidadoApplication"))
                .isInstanceOf(ClassNotFoundException.class);

        UUID comerciante = UUID.randomUUID();
        HttpResponse<String> registro = enviar(comerciante.toString(), validos(), COMPROVANTE);
        assertThat(registro.statusCode()).isEqualTo(201);
        HttpResponse<String> consulta = consultar(comerciante.toString(), "2026-11-04", "Pix");
        assertThat(consulta.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(consulta.body(), "$.linhas")).hasSize(1);
        sinal(saida, "sinal registro aceito");
        sinal(saida, "sinal consulta devolveu");

        try (ServerSocket saldoParado = new ServerSocket(0)) {
            Throwable falha = catchThrowable(() -> pedirSaldo(
                    saldoParado.getLocalPort(), comerciante.toString(), "2026-11-04"));
            assertThat(falha).isInstanceOf(IOException.class);
            assertThat(String.valueOf(falha)).doesNotContain("grupos").doesNotContain("saldo da data");
        }
    }

    private static void sinal(CapturedOutput saida, String frase) {
        assertThat(saida).contains(frase);
        assertThat(linhasDeSinal(saida)).isNotEmpty().allSatisfy(linha -> {
            assertThat(linha).doesNotContain(DESCRICAO).doesNotContain("COMPROVANTE-FORA-DO-SINAL");
        });
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

    private int total() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM lancamento", Integer.class);
    }

    private static Map<String, String> validos() {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("data", "2026-11-04");
        campos.put("valor", "10.50");
        campos.put("direcao", "credito");
        campos.put("tipo_pagamento", "Pix");
        campos.put("descricao", DESCRICAO);
        return campos;
    }

    private static Map<String, String> campos(String nome, String valor) {
        Map<String, String> campos = validos();
        campos.put(nome, valor);
        return campos;
    }

    private HttpResponse<String> enviar(String comerciante, Map<String, String> campos, byte[] comprovante)
            throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/lancamentos"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo(campos, comprovante)));
        if (comerciante != null) {
            pedido.header("X-Comerciante-Id", comerciante);
        }
        return HttpClient.newHttpClient().send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> corrigir(String comerciante, String id, Map<String, String> campos, byte[] comprovante)
            throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/lancamentos/" + id))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(corpo(campos, comprovante)));
        if (comerciante != null) {
            pedido.header("X-Comerciante-Id", comerciante);
        }
        return HttpClient.newHttpClient().send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> consultar(String comerciante, String data, String tipoPagamento) throws Exception {
        String consulta = "data=" + URLEncoder.encode(data, StandardCharsets.UTF_8)
                + "&tipo_pagamento=" + URLEncoder.encode(tipoPagamento, StandardCharsets.UTF_8);
        HttpRequest.Builder pedido = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/lancamentos?" + consulta))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (comerciante != null) {
            pedido.header("X-Comerciante-Id", comerciante);
        }
        return HttpClient.newHttpClient().send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> abrir(String comerciante, String id) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/lancamentos/" + id + "/comprovante"))
                .timeout(Duration.ofSeconds(10))
                .header("X-Comerciante-Id", comerciante)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void pedirSaldo(int porta, String comerciante, String data) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + porta + "/saldo-diario-consolidado?data=" + data))
                .timeout(Duration.ofSeconds(3))
                .header("X-Comerciante-Id", comerciante)
                .GET()
                .build();
        HttpResponse<String> resposta = HttpClient.newHttpClient()
                .send(pedido, HttpResponse.BodyHandlers.ofString());
        throw new AssertionError("saldo respondeu " + resposta.statusCode() + " " + resposta.body());
    }

    private static byte[] corpo(Map<String, String> campos, byte[] comprovante) {
        StringBuilder texto = new StringBuilder();
        for (Map.Entry<String, String> campo : campos.entrySet()) {
            texto.append("--").append(BOUNDARY).append("\r\n");
            texto.append("Content-Disposition: form-data; name=\"").append(campo.getKey()).append("\"\r\n");
            texto.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
            texto.append(campo.getValue()).append("\r\n");
        }
        byte[] prefixo = texto.toString().getBytes(StandardCharsets.UTF_8);
        String cabecalho = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"comprovante\"; filename=\"comprovante.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        byte[] inicio = cabecalho.getBytes(StandardCharsets.UTF_8);
        byte[] fimParte = "\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] arquivo = new byte[inicio.length + comprovante.length + fimParte.length];
        System.arraycopy(inicio, 0, arquivo, 0, inicio.length);
        System.arraycopy(comprovante, 0, arquivo, inicio.length, comprovante.length);
        System.arraycopy(fimParte, 0, arquivo, inicio.length + comprovante.length, fimParte.length);
        byte[] fecho = ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] corpo = new byte[prefixo.length + arquivo.length + fecho.length];
        System.arraycopy(prefixo, 0, corpo, 0, prefixo.length);
        System.arraycopy(arquivo, 0, corpo, prefixo.length, arquivo.length);
        System.arraycopy(fecho, 0, corpo, prefixo.length + arquivo.length, fecho.length);
        return corpo;
    }

    private static String tipo(HttpResponse<String> resposta) {
        return resposta.headers().firstValue("content-type").orElse("");
    }
}
