import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  avaliarPeriodo,
  limitesDoMes,
  centavosDe,
  centavosVisiveis,
  comercianteValido,
  diaDoCorpo,
  falaValor,
  fraseAceite,
  fraseConsultaRecusada,
  fraseDaLista,
  fraseFalhaComprovante,
  fraseRegistroRecusado,
  campoDaRecusa,
  reais,
  textoDeCentavos,
  textoLegivel,
  valorApi,
  type DiaSaldo,
  type Linha,
} from './regras';

function janeiro(): string[] {
  const datas: string[] = [];
  for (let dia = 1; dia <= 31; dia++) {
    datas.push('2026-01-' + (dia < 10 ? '0' + dia : String(dia)));
  }
  return datas;
}

function dia(data: string, saldo: string, grupos: DiaSaldo['grupos']): DiaSaldo {
  const centavos = centavosDe(saldo);
  if (centavos === null) throw new Error(saldo);
  return { data, saldo, grupos, centavos };
}

describe('valor e frases', () => {
  it('leva 10,50 para a api e para a tela sem perder centavos', () => {
    expect(valorApi('10,50')).toBe('10.50');
    expect(valorApi('1')).toBe('1.00');
    expect(valorApi('1,1')).toBe('1.10');
    expect(reais('10.50')).toBe('R$ 10,50');
    expect(reais('0.00')).toBe('R$ 0,00');
    expect(reais('-2.25')).toBe('R$ -2,25');
    expect(centavosDe('10.50')).toBe(1050);
    expect(centavosDe('-2.25')).toBe(-225);
    expect(centavosDe('10.5')).toBeNull();
    expect(textoDeCentavos(1050 + -225)).toBe('8.25');
    expect(falaValor('10,50')).toBe('10 reais e 50 centavos');
    expect(falaValor('1')).toBe('1 real e 0 centavos');
    expect(falaValor('1,01')).toBe('1 real e 1 centavo');
    expect(falaValor('-2,25')).toBe('menos 2 reais e 25 centavos');
  });

  it('recusa conhecida marca o campo e a desconhecida cai no valor', () => {
    expect(campoDaRecusa('data invalida')).toEqual(['data', 'Data']);
    expect(campoDaRecusa('direcao invalida')).toEqual(['debito', 'Débito']);
    expect(campoDaRecusa('outra')).toEqual(['valor', 'Valor']);
    expect(fraseRegistroRecusado('Data', 'Recusa de lançamento')).toBe('Registro recusado. Data. Recusa de lançamento.');
    expect(fraseRegistroRecusado('Valor', '')).toBe('Registro recusado. Valor.');
    expect(fraseConsultaRecusada('Recusa de lançamento')).toBe('Consulta recusada. Recusa de lançamento.');
    expect(fraseConsultaRecusada('')).toBe('Consulta recusada.');
    expect(fraseDaLista(0, 0)).toBe('Nenhum lançamento.');
    expect(fraseDaLista(1, 4)).toBe('1 linha. Total 4.');
    expect(fraseAceite(true, 1)).toBe('Registro aceito. Total 1.');
    expect(fraseAceite(false, 2)).toBe('Alteração aceita. Total 2.');
  });

  it('comerciante vazio ou invalido nao passa', () => {
    expect(comercianteValido('')).toBe(false);
    expect(comercianteValido(' ')).toBe(false);
    expect(comercianteValido('nao-uuid')).toBe(false);
    expect(comercianteValido('11111111-1111-4111-8111-111111111111')).toBe(true);
  });
});

describe('periodo e saldo', () => {
  it('pede no maximo 31 dias e nao pede periodo invalido', () => {
    const cheio = avaliarPeriodo('2026-01-01', '2026-01-31');
    expect(cheio).toEqual({ tipo: 'dias', datas: janeiro() });
    expect(avaliarPeriodo('2026-01-01', '2026-02-01')).toEqual({
      tipo: 'recusa',
      frase: 'O período passa de 31 dias.',
    });
    expect(avaliarPeriodo('2026-01-05', '2026-01-01')).toEqual({
      tipo: 'recusa',
      frase: 'A data final é anterior à inicial.',
    });
    expect(avaliarPeriodo('', '2026-01-01').tipo).toBe('incompleto');
    expect(avaliarPeriodo('2026-02-31', '2026-02-31').tipo).toBe('incompleto');
    expect(limitesDoMes(new Date(2026, 10, 5))).toEqual({ inicio: '2026-11-01', fim: '2026-11-30' });
    expect(limitesDoMes(new Date(2026, 11, 31))).toEqual({ inicio: '2026-12-01', fim: '2026-12-31' });
    expect(limitesDoMes(new Date(2024, 1, 15))).toEqual({ inicio: '2024-02-01', fim: '2024-02-29' });
    expect(limitesDoMes(new Date(2025, 1, 1))).toEqual({ inicio: '2025-02-01', fim: '2025-02-28' });
    expect(limitesDoMes(new Date(2026, 0, 31))).toEqual({ inicio: '2026-01-01', fim: '2026-01-31' });
    for (const agora of [new Date(2026, 10, 5), new Date(2026, 11, 31), new Date(2025, 1, 1), new Date(2024, 1, 15), new Date(2026, 0, 31)]) {
      const mes = limitesDoMes(agora);
      const periodo = avaliarPeriodo(mes.inicio, mes.fim);
      expect(periodo.tipo).toBe('dias');
    }
  });

  it('soma so dias com grupos e rejeita corpo incompleto', () => {
    const vazio = dia('2026-01-01', '0.00', []);
    const cheio = dia('2026-01-02', '9.50', [
      { tipo: 'Pix', creditos: '10.50', debitos: '2.00', saldo: '8.50' },
    ]);
    const negativo = dia('2026-01-02', '-2.25', [
      { tipo: 'boleto', creditos: '10.50', debitos: '0.00', saldo: '-2.25' },
    ]);
    const positivo = dia('2026-01-01', '10.50', [
      { tipo: 'Pix', creditos: '10.50', debitos: '0.00', saldo: '10.50' },
    ]);
    expect(centavosVisiveis([vazio, cheio])).toBe(950);
    expect(reais(textoDeCentavos(centavosVisiveis([positivo, negativo])))).toBe('R$ 8,25');
    expect(diaDoCorpo('2026-01-02', { grupos: [] })).toBeNull();
    expect(diaDoCorpo('2026-01-02', { grupos: [{ tipo_pagamento: 'Pix' }], 'saldo da data': '1.00' })).toBeNull();
    const lido = diaDoCorpo('2026-01-02', {
      grupos: [
        { tipo_pagamento: 'Pix', 'total de créditos': '10.50', 'total de débitos': '2.00', 'saldo do tipo': '8.50' },
        { tipo_pagamento: 'dinheiro', 'total de créditos': '1.00', 'total de débitos': '0.00', 'saldo do tipo': '1.00' },
      ],
      'saldo da data': '9.50',
    });
    expect(lido?.grupos.map((grupo) => grupo.tipo)).toEqual(['Pix', 'dinheiro']);
  });

  it('comprovante sem texto ou com byte nulo nao vira frase de sucesso', () => {
    const linha: Linha = {
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      data: '2026-01-02',
      valor: '10.50',
      direcao: 'debito',
      tipo_pagamento: 'Pix',
      descricao: 'cafe',
      comprovante: true,
    };
    expect(fraseFalhaComprovante(linha)).toBe('Comprovante não abre. R$ 10,50. cafe');
    expect(textoLegivel(new Uint8Array([0x61, 0]).buffer)).toBe('');
    expect(textoLegivel(new TextEncoder().encode('   ').buffer)).toBe('');
    expect(textoLegivel(new TextEncoder().encode('nota').buffer)).toBe('nota');
  });
});

describe('fonte', () => {
  it('nao calcula com parseFloat, Number( nem porta 8082', () => {
    const proibido = 'Number' + '(';
    const porta = '80' + '82';
    const arquivos = listar(join(process.cwd(), 'src')).filter((arquivo) => !arquivo.endsWith('.spec.ts'));
    expect(arquivos.length).toBeGreaterThan(0);
    for (const arquivo of arquivos) {
      const texto = readFileSync(arquivo, 'utf8');
      expect(texto, arquivo).not.toContain('parseFloat');
      expect(texto, arquivo).not.toContain(proibido);
      expect(texto, arquivo).not.toContain(porta);
    }
  });
});

function listar(dir: string): string[] {
  const saida: string[] = [];
  for (const nome of readdirSync(dir)) {
    const caminho = join(dir, nome);
    if (statSync(caminho).isDirectory()) saida.push(...listar(caminho));
    else saida.push(caminho);
  }
  return saida;
}
