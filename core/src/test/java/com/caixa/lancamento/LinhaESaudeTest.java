package com.caixa.lancamento;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Date;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LinhaESaudeTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    static {
        postgres.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @LocalServerPort
    private int port;

    @Test
    void tabelaTemSoAsColunasDeAd8EUmaMigracao() throws Exception {
        List<Map<String, Object>> colunas = jdbc.queryForList("""
                SELECT column_name, udt_name, numeric_scale, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'lancamento'
                ORDER BY ordinal_position
                """);

        assertThat(colunas).extracting(coluna -> coluna.get("column_name")).containsExactly(
                "id",
                "comerciante_id",
                "data",
                "valor",
                "direcao",
                "tipo_pagamento",
                "descricao",
                "comprovante");

        assertThat(coluna(colunas, "id")).containsEntry("udt_name", "uuid").containsEntry("is_nullable", "NO");
        assertThat(coluna(colunas, "comerciante_id")).containsEntry("udt_name", "uuid").containsEntry("is_nullable", "NO");
        assertThat(coluna(colunas, "data")).containsEntry("udt_name", "date").containsEntry("is_nullable", "NO");
        Map<String, Object> valor = coluna(colunas, "valor");
        assertThat(valor).containsEntry("udt_name", "numeric").containsEntry("is_nullable", "NO");
        assertThat(((Number) valor.get("numeric_scale")).intValue()).isEqualTo(2);
        assertThat(coluna(colunas, "direcao")).containsEntry("udt_name", "text").containsEntry("is_nullable", "NO");
        assertThat(coluna(colunas, "tipo_pagamento")).containsEntry("udt_name", "text").containsEntry("is_nullable", "NO");
        assertThat(coluna(colunas, "descricao")).containsEntry("udt_name", "text").containsEntry("is_nullable", "NO");
        assertThat(coluna(colunas, "comprovante")).containsEntry("udt_name", "bytea").containsEntry("is_nullable", "YES");

        List<String> checks = jdbc.queryForList("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'public.lancamento'::regclass AND contype = 'c'
                ORDER BY conname
                """, String.class);
        assertThat(checks).hasSize(2);
        assertThat(checks).anySatisfy(definicao ->
                assertThat(tokens(definicao)).containsExactlyInAnyOrder("debito", "credito"));
        assertThat(checks).anySatisfy(definicao -> assertThat(tokens(definicao)).containsExactlyInAnyOrder(
                "Pix",
                "cartão de crédito",
                "cartão de débito",
                "dinheiro",
                "boleto",
                "depósito"));

        String indice = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE indexname = 'lancamento_comerciante_data_idx'
                """, String.class);
        assertThat(indice).contains("(comerciante_id, data)");

        String chave = jdbc.queryForObject("""
                SELECT a.attname
                FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                WHERE i.indrelid = 'public.lancamento'::regclass AND i.indisprimary
                """, String.class);
        assertThat(chave).isEqualTo("id");

        Integer migracoes = jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog", Integer.class);
        assertThat(migracoes).isEqualTo(1);
        String execucao = jdbc.queryForObject("SELECT exectype FROM databasechangelog", String.class);
        assertThat(execucao).isEqualTo("EXECUTED");

        Resource[] scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*.sql");
        assertThat(scripts).isEmpty();
        Resource[] changelogs = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/changelog/db.changelog-master.xml");
        assertThat(changelogs).hasSize(1);
    }

    @Test
    void sobeSemOConsolidadoERespondeSaude() throws Exception {
        try (Connection conexao = dataSource.getConnection()) {
            assertThat(conexao.isReadOnly()).isFalse();
        }

        HttpResponse<String> resposta = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.read(resposta.body(), "$.status").toString()).isEqualTo("UP");
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('public.lancamento')::text", String.class)).isEqualTo("lancamento");
    }

    @Test
    void valorNumericoEComprovanteOpcional() {
        UUID comerciante = UUID.randomUUID();
        List<String> tipos = List.of(
                "Pix",
                "cartão de crédito",
                "cartão de débito",
                "dinheiro",
                "boleto",
                "depósito");
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (String tipo : tipos) {
            UUID id = UUID.randomUUID();
            ids.put(tipo, id);
            byte[] comprovante = "dinheiro".equals(tipo) ? new byte[]{1, 2, 3} : null;
            jdbc.update("""
                    INSERT INTO lancamento (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao, comprovante)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, comerciante, Date.valueOf("2026-11-04"), new BigDecimal("10.50"),
                    "credito", tipo, "venda " + tipo, comprovante);
        }

        BigDecimal valor = jdbc.queryForObject(
                "SELECT valor FROM lancamento WHERE id = ?", BigDecimal.class, ids.get("Pix"));
        assertThat(valor).isEqualByComparingTo("10.50");
        assertThat(valor.scale()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT comprovante FROM lancamento WHERE id = ?", byte[].class, ids.get("Pix"))).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT comprovante FROM lancamento WHERE id = ?", byte[].class, ids.get("dinheiro")))
                .containsExactly(1, 2, 3);
        assertThat(jdbc.queryForList(
                "SELECT tipo_pagamento FROM lancamento WHERE comerciante_id = ?", String.class, comerciante))
                .containsExactlyInAnyOrderElementsOf(tipos);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO lancamento (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), comerciante, Date.valueOf("2026-11-04"), new BigDecimal("1.00"),
                "outro", "Pix", "direcao invalida"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO lancamento (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), comerciante, Date.valueOf("2026-11-04"), new BigDecimal("1.00"),
                "debito", "cheque", "tipo invalido"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO lancamento (id, comerciante_id, data, valor, direcao, tipo_pagamento, descricao)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), comerciante, Date.valueOf("2026-11-04"), new BigDecimal("1.00"),
                "debito", "Pix", null))
                .isInstanceOf(DataAccessException.class);
    }

    private static Set<String> tokens(String definicao) {
        Matcher encontrados = Pattern.compile("'([^']*)'").matcher(definicao);
        Set<String> tokens = new LinkedHashSet<>();
        while (encontrados.find()) {
            tokens.add(encontrados.group(1));
        }
        return tokens;
    }

    private static Map<String, Object> coluna(List<Map<String, Object>> colunas, String nome) {
        return colunas.stream()
                .filter(coluna -> nome.equals(coluna.get("column_name")))
                .findFirst()
                .orElseThrow();
    }
}
