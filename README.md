# Controle de fluxo de caixa do comerciante

O comerciante registra débito e crédito do próprio caixa, consulta e corrige o lançamento, abre o comprovante e pede o saldo diário consolidado. Débito e crédito são a direção do lançamento. O tipo de pagamento é um rótulo fechado: Pix, cartão de crédito, cartão de débito, dinheiro, boleto ou depósito.

A tela e a API são processos separados. O saldo de cada dia e o total de cada tipo saem da API. A tela soma esses saldos diários quando mostra o período.

Cada chamada carrega o cabeçalho `X-Comerciante-Id`. O identificador da tela fica em `front/src/environments/environment.ts`, na propriedade `comercianteId`. Vazio ou inválido, a tela mostra "Comerciante ausente." e não chama a API.

## Portas

| Processo | Porta | O que atende |
| --- | --- | --- |
| API do caixa | 8081 | `POST /lancamentos`, `PUT /lancamentos/{id}`, `GET /lancamentos`, `GET /lancamentos/{id}/comprovante`, `GET /saldo-diario-consolidado` |
| Tela | 4200 | `http://127.0.0.1:4200/` |
| PostgreSQL 18.6 | 5432 | banco `caixa` do `compose.yaml` |

A tela chama `http://127.0.0.1:8081` direto. Não há proxy.

## Como subir

Na raiz, com Docker:

```
docker compose up --build
```

O compose sobe o PostgreSQL 18.6, a API na 8081 e a tela na 4200. A API espera o Postgres saudável. A API usa Java 17. A imagem da tela usa Node 22.22.3.

Abra `http://127.0.0.1:4200/`.

## Testes

O pico do consolidado é o alvo numérico do desafio: 50 pedidos de saldo por segundo, durante 10 segundos, 500 no total, com perda de no máximo 5%. Perda é não devolver o saldo da data pedida, com os totais de cada tipo. No meio do pico, um registro e uma consulta completam na mesma API.

O teste sobe o próprio PostgreSQL 18.6. O Docker precisa estar no ar. Java 17.

```
mvn -f core/pom.xml test -Dtest=PicoDoConsolidadoTest
```

A suíte da API, no mesmo comando sem `-Dtest`, inclui esse pico. A suíte da tela, na pasta `front`:

```
npm test
```

## Documentação

Decisões, domínio, requisitos não funcionais e a resposta ao desafio estão em `architecture.md`.
