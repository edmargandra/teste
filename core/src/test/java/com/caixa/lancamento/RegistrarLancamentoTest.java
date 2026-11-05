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

import java.math.BigDecimal;
import java.net.URI;
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
class RegistrarLancamentoTest {

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
    void registroValidoSemComprovanteGravaEResponde201() throws Exception {
        UUID comerciante = UUID.randomUUID();
        HttpResponse<String> resposta = enviar(comerciante.toString(), validos(), null);

        assertThat(resposta.statusCode()).isEqualTo(201);
        assertThat(tipo(resposta)).startsWith("application/json");
        UUID id = UUID.fromString(JsonPath.read(resposta.body(), "$.id"));

        Map<String, Object> linha = jdbc.queryForMap("SELECT * FROM lancamento WHERE id = ?", id);
        assertThat(linha.get("comerciante_id")).isEqualTo(comerciante);
        assertThat(linha.get("data")).isEqualTo(Date.valueOf("2026-11-04"));
        assertThat((BigDecimal) linha.get("valor")).isEqualByComparingTo("10.50");
        assertThat(linha.get("direcao")).isEqualTo("credito");
        assertThat(linha.get("tipo_pagamento")).isEqualTo("Pix");
        assertThat(linha.get("descricao")).isEqualTo("venda do dia");
        assertThat(jdbc.queryForObject(
                "SELECT comprovante FROM lancamento WHERE id = ?", byte[].class, id)).isNull();
    }

    @Test
    void registroValidoComComprovanteGuardaOsBytes() throws Exception {
        UUID comerciante = UUID.randomUUID();
        byte[] comprovante = new byte[]{9, 8, 7, 6};
        Map<String, String> campos = validos();
        campos.put("direcao", "debito");
        campos.put("tipo_pagamento", "cartão de débito");

        HttpResponse<String> resposta = enviar(comerciante.toString(), campos, comprovante);

        assertThat(resposta.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(resposta.body(), "$.id"));
        assertThat(jdbc.queryForObject(
                "SELECT comprovante FROM lancamento WHERE id = ?", byte[].class, id))
                .containsExactly(9, 8, 7, 6);
        assertThat(jdbc.queryForObject(
                "SELECT tipo_pagamento FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("cartão de débito");
        assertThat(jdbc.queryForObject(
                "SELECT direcao FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("debito");
        assertThat(jdbc.queryForObject(
                "SELECT comerciante_id FROM lancamento WHERE id = ?", UUID.class, id))
                .isEqualTo(comerciante);
    }

    @Test
    void registraOsOutrosTiposDePagamento() throws Exception {
        for (String tipoPagamento : List.of("cartão de crédito", "dinheiro", "boleto", "depósito")) {
            UUID comerciante = UUID.randomUUID();
            HttpResponse<String> resposta = enviar(comerciante.toString(), campos("tipo_pagamento", tipoPagamento), null);

            assertThat(resposta.statusCode()).isEqualTo(201);
            UUID id = UUID.fromString(JsonPath.read(resposta.body(), "$.id"));
            assertThat(jdbc.queryForObject(
                    "SELECT tipo_pagamento FROM lancamento WHERE id = ?", String.class, id))
                    .isEqualTo(tipoPagamento);
        }
    }

    @Test
    void dataInvalidaOuAusenteNaoGravaEOutroDiaIsoEntra() throws Exception {
        Map<String, String> semData = validos();
        semData.remove("data");
        recusa(semData);
        recusa(campos("data", "04/11/2026"));
        recusa(campos("data", "2026-02-31"));

        HttpResponse<String> resposta = enviar(UUID.randomUUID().toString(), campos("data", "2026-01-15"), null);
        assertThat(resposta.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(resposta.body(), "$.id"));
        assertThat(jdbc.queryForObject(
                "SELECT to_char(data, 'YYYY-MM-DD') FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("2026-01-15");
    }

    @Test
    void identidadeAusenteOuInvalidaNaoGrava() throws Exception {
        int antes = total();

        HttpResponse<String> semHeader = enviar(null, validos(), null);
        HttpResponse<String> invalido = enviar("nao-e-uuid", validos(), null);

        assertThat(semHeader.statusCode()).isEqualTo(400);
        assertThat(tipo(semHeader)).startsWith("application/problem+json");
        assertThat(invalido.statusCode()).isEqualTo(400);
        assertThat(tipo(invalido)).startsWith("application/problem+json");
        assertThat(total()).isEqualTo(antes);
    }

    @Test
    void valorDirecaoTipoOuDescricaoInvalidosNaoGravam() throws Exception {
        recusa(campos("valor", "0.00"));
        recusa(campos("valor", "-10.50"));
        recusa(campos("valor", "10.555"));
        recusa(campos("valor", "10.5"));
        recusa(campos("direcao", "entrada"));
        recusa(campos("tipo_pagamento", "cheque"));
        Map<String, String> semDescricao = validos();
        semDescricao.remove("descricao");
        recusa(semDescricao);
        recusa(campos("descricao", "   "));
    }

    @Test
    void consolidadoAusenteDoClasspathNaoImpedeOAceite() throws Exception {
        assertThatThrownBy(() -> Class.forName("com.caixa.consolidado.ConsolidadoApplication"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(context.getBeansOfType(RestTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(RestClient.class)).isEmpty();

        UUID comerciante = UUID.randomUUID();
        HttpResponse<String> resposta = enviar(comerciante.toString(), validos(), null);

        assertThat(resposta.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(resposta.body(), "$.id"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM lancamento WHERE id = ? AND comerciante_id = ?",
                Integer.class, id, comerciante)).isEqualTo(1);
    }

    @Test
    void correcaoSemArquivoNovoMantemOIdEOComprovante() throws Exception {
        UUID comerciante = UUID.randomUUID();
        byte[] arquivo = new byte[]{1, 2, 3, 4};
        HttpResponse<String> criado = enviar(comerciante.toString(), validos(), arquivo);
        assertThat(criado.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(criado.body(), "$.id"));

        Map<String, String> campos = validos();
        campos.put("data", "2026-11-05");
        campos.put("valor", "11.00");
        campos.put("direcao", "debito");
        campos.put("tipo_pagamento", "dinheiro");
        campos.put("descricao", "venda corrigida");
        HttpResponse<String> resposta = corrigir(comerciante.toString(), id.toString(), campos, null);

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(tipo(resposta)).startsWith("application/json");
        assertThat(JsonPath.read(resposta.body(), "$.id").toString()).isEqualTo(id.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lancamento WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT comprovante FROM lancamento WHERE id = ?", byte[].class, id))
                .containsExactly(1, 2, 3, 4);
        assertThat(jdbc.queryForObject("SELECT descricao FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("venda corrigida");
        assertThat(jdbc.queryForObject(
                "SELECT to_char(data, 'YYYY-MM-DD') FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("2026-11-05");
        assertThat((BigDecimal) jdbc.queryForObject("SELECT valor FROM lancamento WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("11.00");
        assertThat(jdbc.queryForObject("SELECT direcao FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("debito");
        assertThat(jdbc.queryForObject("SELECT tipo_pagamento FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("dinheiro");
    }

    @Test
    void correcaoComArquivoNovoSubstituiOComprovanteNoMesmoId() throws Exception {
        UUID comerciante = UUID.randomUUID();
        HttpResponse<String> criado = enviar(comerciante.toString(), validos(), new byte[]{1, 2});
        UUID id = UUID.fromString(JsonPath.read(criado.body(), "$.id"));

        HttpResponse<String> resposta = corrigir(comerciante.toString(), id.toString(), validos(), new byte[]{5, 6});

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.read(resposta.body(), "$.id").toString()).isEqualTo(id.toString());
        assertThat(jdbc.queryForObject("SELECT comprovante FROM lancamento WHERE id = ?", byte[].class, id))
                .containsExactly(5, 6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lancamento WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void correcaoInvalidaNaoGravaENaoCriaOutroId() throws Exception {
        UUID comerciante = UUID.randomUUID();
        HttpResponse<String> criado = enviar(comerciante.toString(), validos(), new byte[]{9});
        UUID id = UUID.fromString(JsonPath.read(criado.body(), "$.id"));
        int antes = total();

        HttpResponse<String> recusada = corrigir(comerciante.toString(), id.toString(), campos("valor", "0.00"), null);
        assertThat(recusada.statusCode()).isEqualTo(400);
        assertThat(tipo(recusada)).startsWith("application/problem+json");
        assertThat(jdbc.queryForObject("SELECT descricao FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("venda do dia");
        assertThat(jdbc.queryForObject("SELECT comprovante FROM lancamento WHERE id = ?", byte[].class, id))
                .containsExactly(9);

        HttpResponse<String> ausente = corrigir(comerciante.toString(), UUID.randomUUID().toString(), validos(), null);
        assertThat(ausente.statusCode()).isEqualTo(400);
        assertThat(total()).isEqualTo(antes);

        HttpResponse<String> outroCaixa = corrigir(
                UUID.randomUUID().toString(), id.toString(), campos("descricao", "invasao"), null);
        assertThat(outroCaixa.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT descricao FROM lancamento WHERE id = ?", String.class, id))
                .isEqualTo("venda do dia");
    }

    private void recusa(Map<String, String> campos) throws Exception {
        UUID comerciante = UUID.randomUUID();
        int antes = total();
        HttpResponse<String> resposta = enviar(comerciante.toString(), campos, null);
        assertThat(resposta.statusCode()).isEqualTo(400);
        assertThat(tipo(resposta)).startsWith("application/problem+json");
        assertThat(total()).isEqualTo(antes);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM lancamento WHERE comerciante_id = ?",
                Integer.class, comerciante)).isZero();
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
        campos.put("descricao", "venda do dia");
        return campos;
    }

    private static Map<String, String> campos(String nome, String valor) {
        Map<String, String> campos = validos();
        campos.put(nome, valor);
        return campos;
    }

    private HttpResponse<String> enviar(String comerciante, Map<String, String> campos, byte[] comprovante)
            throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/lancamentos"))
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
                        URI.create("http://localhost:" + port + "/lancamentos/" + id))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(corpo(campos, comprovante)));
        if (comerciante != null) {
            pedido.header("X-Comerciante-Id", comerciante);
        }
        return HttpClient.newHttpClient().send(pedido.build(), HttpResponse.BodyHandlers.ofString());
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
        byte[] arquivo = new byte[0];
        if (comprovante != null) {
            String cabecalho = "--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"comprovante\"; filename=\"comprovante.bin\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            byte[] inicio = cabecalho.getBytes(StandardCharsets.UTF_8);
            byte[] fimParte = "\r\n".getBytes(StandardCharsets.UTF_8);
            arquivo = new byte[inicio.length + comprovante.length + fimParte.length];
            System.arraycopy(inicio, 0, arquivo, 0, inicio.length);
            System.arraycopy(comprovante, 0, arquivo, inicio.length, comprovante.length);
            System.arraycopy(fimParte, 0, arquivo, inicio.length + comprovante.length, fimParte.length);
        }
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
