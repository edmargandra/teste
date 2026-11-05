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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsultarLancamentoTest {

    private static final byte[] ARQUIVO = "NAO-VAZAR-BYTES".getBytes(StandardCharsets.UTF_8);
    private static final UUID PIX_COM_ARQUIVO = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID PIX_SEM_ARQUIVO = UUID.fromString("00000000-0000-4000-8000-000000000001");

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
    void listaSoODiaEOTipoDesseCaixaComValorEmTexto() throws Exception {
        UUID caixa = UUID.randomUUID();
        UUID outro = UUID.randomUUID();
        gravar(PIX_COM_ARQUIVO, caixa, "2026-11-04", "10.50", "credito", "Pix", "venda do dia", ARQUIVO);
        gravar(PIX_SEM_ARQUIVO, caixa, "2026-11-04", "2.00", "debito", "Pix", "troco", null);
        gravar(UUID.randomUUID(), caixa, "2026-11-04", "3.00", "credito", "dinheiro", "especie", null);
        gravar(UUID.randomUUID(), caixa, "2026-11-05", "4.00", "credito", "Pix", "outro dia", null);
        gravar(UUID.randomUUID(), outro, "2026-11-04", "9.00", "credito", "Pix", "outro caixa", ARQUIVO);

        HttpResponse<byte[]> resposta = consultar(caixa.toString(), "2026-11-04", "Pix");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(tipo(resposta)).startsWith("application/json");
        String corpo = texto(resposta);
        List<Map<String, Object>> linhas = JsonPath.read(corpo, "$.linhas");
        assertThat(linhas).hasSize(2);
        assertThat(totalDaPagina(corpo)).isEqualTo(2);
        assertThat(corpo.trim()).startsWith("{");
        assertThat(linhas).extracting(linha -> linha.get("id")).containsExactly(
                PIX_SEM_ARQUIVO.toString(),
                PIX_COM_ARQUIVO.toString());
        assertThat(jdbc.queryForList("""
                SELECT id::text FROM lancamento
                WHERE comerciante_id = ? AND data = ? AND tipo_pagamento = ?
                ORDER BY id
                """, String.class, caixa, Date.valueOf("2026-11-04"), "Pix"))
                .containsExactly(PIX_SEM_ARQUIVO.toString(), PIX_COM_ARQUIVO.toString());

        Map<String, Object> semArquivo = linhas.get(0);
        assertThat(semArquivo.keySet()).containsExactly(
                "id", "data", "valor", "direcao", "tipo_pagamento", "descricao", "comprovante");
        assertThat(semArquivo.get("data")).isEqualTo("2026-11-04");
        assertThat(semArquivo.get("valor")).isEqualTo("2.00");
        assertThat(semArquivo.get("direcao")).isEqualTo("debito");
        assertThat(semArquivo.get("tipo_pagamento")).isEqualTo("Pix");
        assertThat(semArquivo.get("descricao")).isEqualTo("troco");
        assertThat(semArquivo.get("comprovante")).isEqualTo(false);

        Map<String, Object> comArquivo = linhas.get(1);
        assertThat(comArquivo.get("valor")).isEqualTo("10.50");
        assertThat(comArquivo.get("direcao")).isEqualTo("credito");
        assertThat(comArquivo.get("descricao")).isEqualTo("venda do dia");
        assertThat(comArquivo.get("comprovante")).isEqualTo(true);
        assertThat(corpo).contains("\"valor\":\"2.00\"").contains("\"valor\":\"10.50\"");
        assertThat(corpo).doesNotContain("NAO-VAZAR-BYTES");
        assertThat(corpo).doesNotContain(Base64.getEncoder().encodeToString(ARQUIVO));
        assertThat(context.getBeansOfType(RestTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(RestClient.class)).isEmpty();
    }

    @Test
    void listaVaziaQuandoOCaixaNaoTemLinha() throws Exception {
        UUID caixa = UUID.randomUUID();
        gravar(UUID.randomUUID(), UUID.randomUUID(), "2026-11-04", "10.50", "credito", "Pix", "alheio", null);

        HttpResponse<byte[]> resposta = consultar(caixa.toString(), "2026-11-04", "Pix");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(tipo(resposta)).startsWith("application/json");
        assertThat((List<?>) JsonPath.read(texto(resposta), "$.linhas")).isEmpty();
        assertThat(totalDaPagina(texto(resposta))).isEqualTo(0);
    }

    @Test
    void outroCaixaNaoRecebeALinha() throws Exception {
        UUID caixaA = UUID.randomUUID();
        UUID caixaB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        gravar(id, caixaA, "2026-11-04", "10.50", "credito", "cartão de crédito", "venda", ARQUIVO);

        HttpResponse<byte[]> resposta = consultar(caixaB.toString(), "2026-11-04", "cartão de crédito");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(texto(resposta), "$.linhas")).isEmpty();
        assertThat(totalDaPagina(texto(resposta))).isEqualTo(0);
        assertThat(texto(resposta)).doesNotContain(id.toString());
    }

    @Test
    void osSeisTiposSemLinhaDevolvemListaVazia() throws Exception {
        UUID caixa = UUID.randomUUID();
        for (String tipo : List.of(
                "Pix", "cartão de crédito", "cartão de débito", "dinheiro", "boleto", "depósito")) {
            HttpResponse<byte[]> resposta = consultar(caixa.toString(), "2026-03-01", tipo);
            assertThat(resposta.statusCode()).isEqualTo(200);
            assertThat((List<?>) JsonPath.read(texto(resposta), "$.linhas")).isEmpty();
            assertThat(totalDaPagina(texto(resposta))).isEqualTo(0);
        }
    }

    @Test
    void identidadeDataOuTipoInvalidosNaoDevolvemLinha() throws Exception {
        UUID caixa = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        gravar(id, caixa, "2026-11-04", "10.50", "credito", "Pix", "venda do dia", ARQUIVO);

        List<HttpResponse<byte[]>> recusas = new ArrayList<>();
        recusas.add(consultar(null, "2026-11-04", "Pix"));
        recusas.add(consultar("nao-e-uuid", "2026-11-04", "Pix"));
        recusas.add(consultar(caixa.toString(), "04/11/2026", "Pix"));
        recusas.add(consultar(caixa.toString(), "2026-02-31", "Pix"));
        recusas.add(consultar(caixa.toString(), "2026-11-04", "cheque"));
        recusas.add(consultar(caixa.toString(), "2026-11-04", "PIX"));
        recusas.add(consultar(caixa.toString(), "2026-11-04", "Pix", "entrada"));
        recusas.add(consultar(caixa.toString(), "2026-11-04", "Pix", null, "-1", null));
        recusas.add(consultar(caixa.toString(), "2026-11-04", "Pix", null, null, "0"));

        for (HttpResponse<byte[]> resposta : recusas) {
            assertThat(resposta.statusCode()).isEqualTo(400);
            assertThat(tipo(resposta)).startsWith("application/problem+json");
            assertThat(texto(resposta)).doesNotContain(id.toString());
            assertThat(texto(resposta)).doesNotContain("NAO-VAZAR-BYTES");
        }
    }

    @Test
    void comprovanteDoProprioCaixaDevolveOsMesmosBytes() throws Exception {
        UUID caixa = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        gravar(id, caixa, "2026-11-04", "10.50", "debito", "dinheiro", "compra", ARQUIVO);

        HttpResponse<byte[]> resposta = abrir(caixa.toString(), id.toString());

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(tipo(resposta)).startsWith("application/octet-stream");
        assertThat(resposta.body()).containsExactly(ARQUIVO);
    }

    @Test
    void comprovanteDeOutroCaixaIdInexistenteOuSemBytesNaoSai() throws Exception {
        UUID caixaA = UUID.randomUUID();
        UUID caixaB = UUID.randomUUID();
        UUID comBytes = UUID.randomUUID();
        UUID semBytes = UUID.randomUUID();
        gravar(comBytes, caixaA, "2026-11-04", "10.50", "credito", "boleto", "boleto pago", ARQUIVO);
        gravar(semBytes, caixaA, "2026-11-04", "8.00", "credito", "boleto", "sem arquivo", null);

        List<HttpResponse<byte[]>> recusas = List.of(
                abrir(caixaB.toString(), comBytes.toString()),
                abrir(caixaA.toString(), UUID.randomUUID().toString()),
                abrir(caixaA.toString(), "nao-e-uuid"),
                abrir(null, comBytes.toString()),
                abrir("nao-e-uuid", comBytes.toString()),
                abrir(caixaA.toString(), semBytes.toString()));

        for (HttpResponse<byte[]> resposta : recusas) {
            assertThat(resposta.statusCode()).isEqualTo(400);
            assertThat(tipo(resposta)).startsWith("application/problem+json");
            assertThat(resposta.body()).isNotEqualTo(ARQUIVO);
            assertThat(texto(resposta)).doesNotContain("NAO-VAZAR-BYTES");
        }
    }

    @Test
    void semDataTipoNemDirecaoDevolvePaginaComLinhasETotal() throws Exception {
        UUID caixa = UUID.randomUUID();
        UUID outro = UUID.randomUUID();
        UUID debito = UUID.fromString("30000000-0000-4000-8000-000000000001");
        UUID credito = UUID.fromString("30000000-0000-4000-8000-000000000002");
        UUID alheio = UUID.randomUUID();
        gravar(debito, caixa, "2026-11-04", "2.00", "debito", "Pix", "troco", null);
        gravar(credito, caixa, "2026-11-06", "4.00", "credito", "dinheiro", "outro dia", null);
        gravar(alheio, outro, "2026-11-04", "9.00", "credito", "Pix", "outro caixa", null);

        HttpResponse<byte[]> resposta = consultar(caixa.toString(), null, null);
        assertThat(resposta.statusCode()).isEqualTo(200);
        String corpo = texto(resposta);
        List<Map<String, Object>> linhas = JsonPath.read(corpo, "$.linhas");
        assertThat(linhas).extracting(linha -> linha.get("id")).containsExactly(debito.toString(), credito.toString());
        assertThat(totalDaPagina(corpo)).isEqualTo(2);
        assertThat(corpo).doesNotContain(alheio.toString());

        HttpResponse<byte[]> vazios = consultar(caixa.toString(), "", "", "", null, null);
        assertThat(vazios.statusCode()).isEqualTo(200);
        assertThat(totalDaPagina(texto(vazios))).isEqualTo(2);

        HttpResponse<byte[]> soDebito = consultar(caixa.toString(), null, null, "debito", null, null);
        assertThat(soDebito.statusCode()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) JsonPath.read(texto(soDebito), "$.linhas"))
                .extracting(linha -> linha.get("id"))
                .containsExactly(debito.toString());
        assertThat(totalDaPagina(texto(soDebito))).isEqualTo(1);
    }

    @Test
    void aPaginaTrazNoMaximoVinteLinhasEOTotal() throws Exception {
        UUID caixa = UUID.randomUUID();
        UUID primeiro = null;
        UUID vigesimo = null;
        UUID ultimo = null;
        for (int i = 1; i <= 21; i++) {
            UUID id = UUID.fromString(String.format("20000000-0000-4000-8000-%012d", i));
            gravar(id, caixa, "2026-11-04", "1.00", "credito", "Pix", "linha " + i, null);
            if (i == 1) {
                primeiro = id;
            }
            if (i == 20) {
                vigesimo = id;
            }
            if (i == 21) {
                ultimo = id;
            }
        }

        HttpResponse<byte[]> semSize = consultar(caixa.toString(), null, null);
        assertThat(semSize.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(texto(semSize), "$.linhas")).hasSize(20);
        assertThat(totalDaPagina(texto(semSize))).isEqualTo(21);

        HttpResponse<byte[]> primeira = consultar(caixa.toString(), null, null, null, "0", "20");
        assertThat(primeira.statusCode()).isEqualTo(200);
        List<Map<String, Object>> linhas = JsonPath.read(texto(primeira), "$.linhas");
        assertThat(linhas).hasSize(20);
        assertThat(linhas.get(0).get("id")).isEqualTo(primeiro.toString());
        assertThat(linhas.get(19).get("id")).isEqualTo(vigesimo.toString());
        assertThat(totalDaPagina(texto(primeira))).isEqualTo(21);

        HttpResponse<byte[]> segunda = consultar(caixa.toString(), null, null, null, "1", "50");
        assertThat(segunda.statusCode()).isEqualTo(200);
        List<Map<String, Object>> resto = JsonPath.read(texto(segunda), "$.linhas");
        assertThat(resto).extracting(linha -> linha.get("id")).containsExactly(ultimo.toString());
        assertThat(totalDaPagina(texto(segunda))).isEqualTo(21);
    }

    private void gravar(UUID id, UUID comerciante, String data, String valor, String direcao,
                        String tipoPagamento, String descricao, byte[] comprovante) {
        jdbc.update("""
                INSERT INTO lancamento
                    (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao, comprovante)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, comerciante, Date.valueOf(data), new java.math.BigDecimal(valor),
                direcao, tipoPagamento, descricao, comprovante);
    }

    private HttpResponse<byte[]> consultar(String comerciante, String data, String tipoPagamento) throws Exception {
        return consultar(comerciante, data, tipoPagamento, null, null, null);
    }

    private HttpResponse<byte[]> consultar(String comerciante, String data, String tipoPagamento, String direcao)
            throws Exception {
        return consultar(comerciante, data, tipoPagamento, direcao, null, null);
    }

    private HttpResponse<byte[]> consultar(String comerciante, String data, String tipoPagamento, String direcao,
                                           String page, String size) throws Exception {
        List<String> partes = new ArrayList<>();
        if (data != null) {
            partes.add("data=" + URLEncoder.encode(data, StandardCharsets.UTF_8));
        }
        if (tipoPagamento != null) {
            partes.add("tipo_pagamento=" + URLEncoder.encode(tipoPagamento, StandardCharsets.UTF_8));
        }
        if (direcao != null) {
            partes.add("direcao=" + URLEncoder.encode(direcao, StandardCharsets.UTF_8));
        }
        if (page != null) {
            partes.add("page=" + URLEncoder.encode(page, StandardCharsets.UTF_8));
        }
        if (size != null) {
            partes.add("size=" + URLEncoder.encode(size, StandardCharsets.UTF_8));
        }
        String consulta = partes.isEmpty() ? "" : "?" + String.join("&", partes);
        return pedir(comerciante, URI.create("http://localhost:" + port + "/lancamentos" + consulta));
    }

    private HttpResponse<byte[]> abrir(String comerciante, String id) throws Exception {
        return pedir(comerciante, URI.create("http://localhost:" + port + "/lancamentos/" + id + "/comprovante"));
    }

    private HttpResponse<byte[]> pedir(String comerciante, URI uri) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (comerciante != null) {
            pedido.header("X-Comerciante-Id", comerciante);
        }
        return HttpClient.newHttpClient().send(pedido.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String tipo(HttpResponse<byte[]> resposta) {
        return resposta.headers().firstValue("content-type").orElse("");
    }

    private static String texto(HttpResponse<byte[]> resposta) {
        return new String(resposta.body(), StandardCharsets.UTF_8);
    }

    private static int totalDaPagina(String corpo) {
        return ((Number) JsonPath.read(corpo, "$.total")).intValue();
    }
}
