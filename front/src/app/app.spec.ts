import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { environment } from '../environments/environment';
import { App } from './app';
import { limitesDoMes } from './regras';

const CAIXA = '11111111-1111-4111-8111-111111111111';
const ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const OUTRO = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

interface Chamada {
  url: string;
  method: string;
  header: string;
  body: BodyInit | null | undefined;
}

let chamadas: Chamada[];
let aoBuscar: (url: string, chamada: Chamada) => Promise<Response> | Response;

function json(status: number, corpo: unknown): Response {
  return new Response(JSON.stringify(corpo), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function problema(detail: string, title = 'Recusa de lançamento'): Response {
  return json(400, { title, detail });
}

function lancamento(id = ID, comprovante = false): Record<string, unknown> {
  return {
    id,
    data: '2026-01-02',
    valor: '10.50',
    direcao: 'debito',
    tipo_pagamento: 'Pix',
    descricao: 'cafe',
    comprovante,
  };
}

function grupo(tipo: string, creditos: string, debitos: string, saldo: string): Record<string, string> {
  return {
    tipo_pagamento: tipo,
    'total de créditos': creditos,
    'total de débitos': debitos,
    'saldo do tipo': saldo,
  };
}

function saldoDia(saldo: string, grupos: Record<string, string>[]): unknown {
  return { grupos, 'saldo da data': saldo };
}

function saldos(): Chamada[] {
  return chamadas.filter((chamada) => chamada.url.includes('/saldo-diario-consolidado'));
}

function lancamentos(): Chamada[] {
  return chamadas.filter((chamada) => new URL(chamada.url).pathname === '/lancamentos' || new URL(chamada.url).pathname.startsWith('/lancamentos/'));
}

async function flush(fixture: ComponentFixture<App>): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  await fixture.whenStable();
}

async function montar(): Promise<ComponentFixture<App>> {
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  await flush(fixture);
  return fixture;
}

function definir(fixture: ComponentFixture<App>, id: string, valor: string): void {
  const el = fixture.nativeElement.querySelector('#' + id) as HTMLInputElement;
  el.value = valor;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  fixture.detectChanges();
}

async function clicar(fixture: ComponentFixture<App>, id: string): Promise<void> {
  (fixture.nativeElement.querySelector('#' + id) as HTMLButtonElement).click();
  await flush(fixture);
}

function aviso(fixture: ComponentFixture<App>, id: string): string {
  return (fixture.nativeElement.querySelector('#' + id) as HTMLElement).textContent ?? '';
}

function desabilitado(fixture: ComponentFixture<App>, id: string): boolean {
  return (fixture.nativeElement.querySelector('#' + id) as HTMLButtonElement).disabled;
}

describe('tela', () => {
  beforeAll(() => {
    const proto = HTMLDialogElement.prototype;
    const abrir = proto.showModal;
    proto.showModal = function (this: HTMLDialogElement) {
      try {
        if (abrir) {
          abrir.call(this);
          return;
        }
      } catch {
        this.open = true;
        return;
      }
      this.open = true;
    };
  });

  beforeEach(async () => {
    environment.comercianteId = '';
    chamadas = [];
    aoBuscar = () => json(200, { linhas: [], total: 0 });
    vi.stubGlobal('fetch', (entrada: RequestInfo | URL, init?: RequestInit) => {
      const url = String(entrada);
      const headers = init?.headers;
      let header = '';
      if (headers && !(headers instanceof Headers) && !Array.isArray(headers)) {
        header = String(headers['X-Comerciante-Id'] ?? '');
      }
      const chamada: Chamada = { url, method: init?.method ?? 'GET', header, body: init?.body };
      chamadas.push(chamada);
      return Promise.resolve(aoBuscar(url, chamada));
    });
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();
  });

  afterEach(() => {
    environment.comercianteId = '';
    vi.unstubAllGlobals();
    TestBed.resetTestingModule();
  });

  it('comerciante ausente nao chama a api', async () => {
    const fixture = await montar();

    expect(aviso(fixture, 'aviso-lista')).toContain('Comerciante ausente.');
    expect(fixture.nativeElement.querySelector('#aviso-lista').classList.contains('recusa')).toBe(true);
    expect(desabilitado(fixture, 'btn-incluir')).toBe(true);
    expect(desabilitado(fixture, 'btn-filtrar')).toBe(true);
    expect(desabilitado(fixture, 'btn-saldo')).toBe(true);
    expect(desabilitado(fixture, 'btn-anterior')).toBe(true);
    expect(desabilitado(fixture, 'btn-seguinte')).toBe(true);
    expect((fixture.nativeElement.querySelector('#filtro-data') as HTMLInputElement).disabled).toBe(true);
    expect(chamadas).toEqual([]);

    await clicar(fixture, 'menu-consolidado');
    expect(fixture.nativeElement.querySelector('#painel-lancamento').hidden).toBe(false);
    expect(chamadas).toEqual([]);
    await fixture.componentInstance.verSaldo();
    fixture.detectChanges();
    expect(saldos()).toEqual([]);
    expect(aviso(fixture, 'aviso-consolidado')).toContain('Comerciante ausente.');
  });

  it('id invalido equivale a comerciante ausente', async () => {
    environment.comercianteId = 'nao-uuid';
    const fixture = await montar();

    expect(aviso(fixture, 'aviso-lista')).toContain('Comerciante ausente.');
    expect(chamadas).toEqual([]);
  });

  it('abertura com uuid lista a primeira pagina', async () => {
    environment.comercianteId = CAIXA;
    const fixture = await montar();
    const url = new URL(chamadas[0]?.url ?? '');

    expect(url.origin + url.pathname).toBe('http://127.0.0.1:8081/lancamentos');
    expect(url.searchParams.get('page')).toBe('0');
    expect(url.searchParams.get('size')).toBe('20');
    expect(chamadas[0]?.method).toBe('GET');
    expect(chamadas[0]?.header).toBe(CAIXA);
    expect(chamadas[0]?.url).not.toContain('80' + '82');
    expect(desabilitado(fixture, 'btn-incluir')).toBe(false);
    expect(aviso(fixture, 'aviso-lista')).toContain('Nenhum lançamento.');
  });

  it('consulta envia filtros e recusa vira frase', async () => {
    environment.comercianteId = CAIXA;
    let etapa = 0;
    aoBuscar = () => {
      etapa += 1;
      if (etapa === 1) return json(200, { linhas: [], total: 0 });
      return problema('filtro');
    };
    const fixture = await montar();
    definir(fixture, 'filtro-data', '2026-01-02');
    definir(fixture, 'filtro-tipo', 'cartão de crédito');
    definir(fixture, 'filtro-direcao', 'debito');
    await clicar(fixture, 'btn-filtrar');
    const url = new URL(chamadas[1]?.url ?? '');

    expect(url.searchParams.get('data')).toBe('2026-01-02');
    expect(url.searchParams.get('tipo_pagamento')).toBe('cartão de crédito');
    expect(url.searchParams.get('direcao')).toBe('debito');
    expect(url.searchParams.get('page')).toBe('0');
    expect(url.searchParams.get('size')).toBe('20');
    expect(chamadas[1]?.header).toBe(CAIXA);
    expect(aviso(fixture, 'aviso-lista')).toBe('Consulta recusada. Recusa de lançamento.');
  });

  it('cabecalho invalido na consulta vira comerciante ausente', async () => {
    environment.comercianteId = CAIXA;
    let etapa = 0;
    aoBuscar = () => {
      etapa += 1;
      return etapa === 1 ? json(200, { linhas: [lancamento()], total: 1 }) : problema('X-Comerciante-Id ausente ou invalido');
    };
    const fixture = await montar();
    expect(fixture.nativeElement.querySelector('#item-' + ID)).not.toBeNull();
    await clicar(fixture, 'btn-filtrar');

    expect(aviso(fixture, 'aviso-lista')).toContain('Comerciante ausente.');
    expect(desabilitado(fixture, 'btn-incluir')).toBe(true);
    expect(fixture.nativeElement.querySelector('#item-' + ID)).toBeNull();
  });

  it('incluir manda multipart e aceita o registro', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (_url, chamada) => {
      if (chamada.method === 'POST') return json(201, { id: ID });
      if (chamadas.filter((item) => item.method === 'POST').length > 0) {
        return json(200, { linhas: [lancamento()], total: 1 });
      }
      return json(200, { linhas: [], total: 0 });
    };
    const fixture = await montar();
    await clicar(fixture, 'btn-incluir');
    definir(fixture, 'data', '2026-01-02');
    definir(fixture, 'valor', '10,50');
    (fixture.nativeElement.querySelector('#debito') as HTMLInputElement).dispatchEvent(new Event('change', { bubbles: true }));
    definir(fixture, 'tipo-registro', 'Pix');
    definir(fixture, 'descricao', 'cafe');
    await clicar(fixture, 'btn-gravar');
    const post = chamadas.find((chamada) => chamada.method === 'POST');
    const corpo = post?.body as FormData;

    expect(post?.url).toBe('http://127.0.0.1:8081/lancamentos');
    expect(post?.header).toBe(CAIXA);
    expect(corpo.get('data')).toBe('2026-01-02');
    expect(corpo.get('valor')).toBe('10.50');
    expect(corpo.get('direcao')).toBe('debito');
    expect(corpo.get('tipo_pagamento')).toBe('Pix');
    expect(corpo.get('descricao')).toBe('cafe');
    expect(corpo.get('comprovante')).toBeNull();
    expect(saldos()).toEqual([]);
    expect((fixture.nativeElement.querySelector('#popup') as HTMLDialogElement).open).toBe(false);
    expect(aviso(fixture, 'aviso-lista')).toBe('Registro aceito. Total 1.');
  });

  it('data vazia e detalhe conhecido marcam o campo', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (_url, chamada) => chamada.method === 'POST' ? problema('data invalida') : json(200, { linhas: [], total: 0 });
    const fixture = await montar();
    await clicar(fixture, 'btn-incluir');
    await clicar(fixture, 'btn-gravar');

    expect(chamadas.some((chamada) => chamada.method === 'POST')).toBe(false);
    expect(aviso(fixture, 'aviso-registro')).toBe('Registro recusado. Data. Recusa de lançamento.');
    expect((fixture.nativeElement.querySelector('#data') as HTMLInputElement).getAttribute('aria-invalid')).toBe('true');

    definir(fixture, 'data', '2026-01-02');
    await clicar(fixture, 'btn-gravar');
    expect(aviso(fixture, 'erro-data')).toContain('Data');
    expect(document.activeElement?.id).toBe('data');
  });

  it('detalhe desconhecido e rede recusam o registro', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (_url, chamada) => chamada.method === 'POST' ? problema('outra') : json(200, { linhas: [], total: 0 });
    const fixture = await montar();
    await clicar(fixture, 'btn-incluir');
    definir(fixture, 'data', '2026-01-02');
    await clicar(fixture, 'btn-gravar');

    expect(aviso(fixture, 'aviso-registro')).toBe('Registro recusado. Valor. Recusa de lançamento.');
    expect((fixture.nativeElement.querySelector('#valor') as HTMLInputElement).classList.contains('recusado')).toBe(true);

    aoBuscar = (_url, chamada) => {
      if (chamada.method === 'POST') throw new Error('rede');
      return json(200, { linhas: [], total: 0 });
    };
    await clicar(fixture, 'btn-gravar');
    expect(aviso(fixture, 'aviso-registro')).toBe('Registro recusado.');
  });

  it('alterar o mesmo id sem arquivo novo preserva o comprovante', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (_url, chamada) => {
      if (chamada.method === 'PUT') return json(200, { id: ID });
      return json(200, { linhas: [lancamento()], total: 1 });
    };
    const fixture = await montar();
    await clicar(fixture, 'alterar-' + ID);
    expect(aviso(fixture, 'titulo-popup')).toBe('Alterar lançamento');
    expect((fixture.nativeElement.querySelector('#manter-comprovante') as HTMLElement).hidden).toBe(false);
    expect((fixture.nativeElement.querySelector('#btn-gravar') as HTMLButtonElement).textContent).toContain('Alterar lançamento');
    await clicar(fixture, 'btn-gravar');
    const put = chamadas.find((chamada) => chamada.method === 'PUT');

    expect(put?.url).toBe('http://127.0.0.1:8081/lancamentos/' + ID);
    expect((put?.body as FormData).get('comprovante')).toBeNull();
    expect(aviso(fixture, 'aviso-lista')).toBe('Alteração aceita. Total 1.');
  });

  it('id diferente do editado recusa a alteracao', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (_url, chamada) => {
      if (chamada.method === 'PUT') return json(200, { id: OUTRO });
      return json(200, { linhas: [lancamento()], total: 1 });
    };
    const fixture = await montar();
    await clicar(fixture, 'alterar-' + ID);
    await clicar(fixture, 'btn-gravar');

    expect(aviso(fixture, 'aviso-registro')).toContain('Registro recusado.');
    expect((fixture.nativeElement.querySelector('#popup') as HTMLDialogElement).open).toBe(true);
    expect(aviso(fixture, 'aviso-lista')).not.toContain('Alteração aceita.');
  });

  it('comprovante reabre o texto e a falha devolve o foco', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (url) => {
      if (url.includes('/comprovante')) return new Response('nota fiscal', { status: 200 });
      return json(200, { linhas: [lancamento(ID, true)], total: 1 });
    };
    const fixture = await montar();
    await clicar(fixture, 'abrir-' + ID);

    expect(chamadas.some((chamada) => chamada.url === 'http://127.0.0.1:8081/lancamentos/' + ID + '/comprovante')).toBe(true);
    expect(fixture.nativeElement.querySelector('#comprovante-' + ID + ' pre').textContent).toBe('nota fiscal');
    expect(document.activeElement?.id).toBe('fechar-' + ID);

    aoBuscar = (url) => url.includes('/comprovante') ? new Response('', { status: 404 }) : json(200, { linhas: [lancamento(ID, true)], total: 1 });
    await clicar(fixture, 'fechar-' + ID);
    await clicar(fixture, 'abrir-' + ID);
    expect(aviso(fixture, 'aviso-lista')).toBe('Comprovante não abre. R$ 10,50. cafe');
    expect(fixture.nativeElement.querySelector('#comprovante-' + ID)).toBeNull();
    expect(document.activeElement?.id).toBe('abrir-' + ID);
  });

  it('saldo pede um dia, esconde dia vazio e soma o periodo', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (url) => {
      if (!url.includes('/saldo-diario-consolidado')) return json(200, { linhas: [lancamento()], total: 1 });
      const data = new URL(url).searchParams.get('data');
      if (data === '2026-01-01') return json(200, saldoDia('0.00', []));
      return json(200, saldoDia('9.50', [
        grupo('Pix', '10.50', '2.00', '8.50'),
        grupo('dinheiro', '1.00', '0.00', '1.00'),
      ]));
    };
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-01');
    definir(fixture, 'consolidado-fim', '2026-01-02');
    await clicar(fixture, 'btn-saldo');
    const texto = (fixture.nativeElement.querySelector('#acordeao') as HTMLElement).textContent ?? '';

    expect(saldos()).toHaveLength(2);
    expect(saldos().every((chamada) => chamada.header === CAIXA)).toBe(true);
    expect(saldos().every((chamada) => !chamada.url.includes('80' + '82'))).toBe(true);
    expect(texto).not.toContain('Data 2026-01-01');
    expect(texto).toContain('Data 2026-01-02 saldo da data R$ 9,50');
    expect(texto.indexOf('Pix')).toBeLessThan(texto.indexOf('dinheiro'));
    expect(texto).toContain('Total do período');
    expect(texto).toContain('R$ 9,50');
    expect(fixture.nativeElement.querySelector('#item-' + ID)).not.toBeNull();
  });

  it('consolidado abre no mes corrente e exige as duas datas', async () => {
    environment.comercianteId = CAIXA;
    const mes = limitesDoMes(new Date());
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    const inicio = fixture.nativeElement.querySelector('#consolidado-inicio') as HTMLInputElement;
    const fim = fixture.nativeElement.querySelector('#consolidado-fim') as HTMLInputElement;

    expect(inicio.value).toBe(mes.inicio);
    expect(fim.value).toBe(mes.fim);

    definir(fixture, 'consolidado-inicio', '2026-01-10');
    definir(fixture, 'consolidado-fim', '');
    await clicar(fixture, 'btn-saldo');
    expect(saldos()).toEqual([]);
    expect(aviso(fixture, 'aviso-consolidado')).toBe('A data final é obrigatória.');

    definir(fixture, 'consolidado-inicio', '');
    definir(fixture, 'consolidado-fim', '2026-01-10');
    await clicar(fixture, 'btn-saldo');
    expect(saldos()).toEqual([]);
    expect(aviso(fixture, 'aviso-consolidado')).toBe('A data inicial é obrigatória.');

    definir(fixture, 'consolidado-inicio', '');
    definir(fixture, 'consolidado-fim', '');
    await clicar(fixture, 'btn-saldo');
    expect(saldos()).toEqual([]);
    expect(aviso(fixture, 'aviso-consolidado')).toBe('A data final é obrigatória.');
    expect(fixture.nativeElement.querySelector('#aviso-consolidado').classList.contains('recusa')).toBe(true);

    fixture.componentInstance.inicio.set('2026-02-31');
    fixture.componentInstance.fim.set('2026-02-31');
    await fixture.componentInstance.verSaldo();
    fixture.detectChanges();
    expect(saldos()).toEqual([]);
    expect(aviso(fixture, 'aviso-consolidado')).toBe('A data é inválida.');

    definir(fixture, 'consolidado-inicio', '2026-01-04');
    definir(fixture, 'consolidado-fim', '2026-01-06');
    await clicar(fixture, 'menu-lancamento');
    await clicar(fixture, 'menu-consolidado');
    expect((fixture.nativeElement.querySelector('#consolidado-inicio') as HTMLInputElement).value).toBe('2026-01-04');
    expect((fixture.nativeElement.querySelector('#consolidado-fim') as HTMLInputElement).value).toBe('2026-01-06');
  });

  it('periodo invalido nao pede saldo', async () => {
    environment.comercianteId = CAIXA;
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-05');
    definir(fixture, 'consolidado-fim', '2026-01-01');
    await clicar(fixture, 'btn-saldo');
    expect(saldos()).toEqual([]);
    expect(aviso(fixture, 'aviso-consolidado')).toBe('A data final é anterior à inicial.');

    definir(fixture, 'consolidado-inicio', '2026-01-01');
    definir(fixture, 'consolidado-fim', '2026-02-01');
    await clicar(fixture, 'btn-saldo');
    expect(saldos()).toEqual([]);
    expect(aviso(fixture, 'aviso-consolidado')).toBe('O período passa de 31 dias.');
    expect(fixture.nativeElement.querySelector('#total-periodo')).toBeNull();
  });

  it('trinta e um dias pedem cada dia', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (url) => {
      if (!url.includes('/saldo-diario-consolidado')) return json(200, { linhas: [], total: 0 });
      return json(200, saldoDia('0.00', []));
    };
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-01');
    definir(fixture, 'consolidado-fim', '2026-01-31');
    await clicar(fixture, 'btn-saldo');
    const datas = saldos().map((chamada) => new URL(chamada.url).searchParams.get('data'));

    expect(datas).toHaveLength(31);
    expect(datas[0]).toBe('2026-01-01');
    expect(datas[30]).toBe('2026-01-31');
    expect(aviso(fixture, 'aviso-consolidado')).toBe('');
    expect(aviso(fixture, 'total-periodo')).toContain('R$ 0,00');
  });

  it('falha de saldo nao apaga lancamentos nem soma parcial', async () => {
    environment.comercianteId = CAIXA;
    let dias = 0;
    aoBuscar = (url) => {
      if (!url.includes('/saldo-diario-consolidado')) return json(200, { linhas: [lancamento()], total: 1 });
      dias += 1;
      if (dias === 1) return json(200, saldoDia('10.50', [grupo('Pix', '10.50', '0.00', '10.50')]));
      return json(500, {});
    };
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-01');
    definir(fixture, 'consolidado-fim', '2026-01-02');
    await clicar(fixture, 'btn-saldo');

    expect(saldos()).toHaveLength(2);
    expect(aviso(fixture, 'aviso-consolidado')).toBe('Consolidado diário indisponível. O saldo diário consolidado não é servido.');
    expect(fixture.nativeElement.querySelector('#total-periodo')).toBeNull();
    expect((fixture.nativeElement.querySelector('#acordeao') as HTMLElement).textContent ?? '').not.toContain('R$');
    expect(fixture.nativeElement.querySelector('#item-' + ID)).not.toBeNull();
  });

  it('soma dois dias com grupos no total do periodo', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (url) => {
      if (!url.includes('/saldo-diario-consolidado')) return json(200, { linhas: [], total: 0 });
      const data = new URL(url).searchParams.get('data');
      if (data === '2026-01-02') return json(200, saldoDia('-2.25', [grupo('boleto', '10.50', '0.00', '-2.25')]));
      return json(200, saldoDia('10.50', [grupo('Pix', '10.50', '0.00', '10.50')]));
    };
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-01');
    definir(fixture, 'consolidado-fim', '2026-01-02');
    await clicar(fixture, 'btn-saldo');

    expect(saldos()).toHaveLength(2);
    expect(aviso(fixture, 'total-periodo')).toContain('Total do período');
    expect(aviso(fixture, 'total-periodo')).toContain('R$ 8,25');
  });

  it('corpo sem saldo da data diz que o saldo nao foi servido', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (url) => url.includes('/saldo-diario-consolidado')
      ? json(200, { grupos: [] })
      : json(200, { linhas: [], total: 0 });
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-02');
    definir(fixture, 'consolidado-fim', '2026-01-02');
    await clicar(fixture, 'btn-saldo');

    expect(aviso(fixture, 'aviso-consolidado')).toBe('Saldo não servido.');
    expect(fixture.nativeElement.querySelector('#total-periodo')).toBeNull();
  });

  it('rede no saldo diz que o consolidado nao e servido', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (url) => {
      if (url.includes('/saldo-diario-consolidado')) throw new Error('rede');
      return json(200, { linhas: [], total: 0 });
    };
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-02');
    definir(fixture, 'consolidado-fim', '2026-01-02');
    await clicar(fixture, 'btn-saldo');

    expect(aviso(fixture, 'aviso-consolidado')).toBe('Consolidado diário indisponível. O saldo diário consolidado não é servido.');
    expect(fixture.nativeElement.querySelector('#total-periodo')).toBeNull();
  });

  it('json invalido diz que o saldo nao foi servido', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = (url) => url.includes('/saldo-diario-consolidado')
      ? new Response('nao-json', { status: 200 })
      : json(200, { linhas: [], total: 0 });
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-02');
    definir(fixture, 'consolidado-fim', '2026-01-02');
    await clicar(fixture, 'btn-saldo');

    expect(aviso(fixture, 'aviso-consolidado')).toBe('Saldo não servido.');
    expect(fixture.nativeElement.querySelector('#total-periodo')).toBeNull();
  });

  it('segundo clique invalido durante o pedido nao apaga o aviso', async () => {
    environment.comercianteId = CAIXA;
    let soltar = (): void => {};
    const trava = new Promise<void>((resolve) => {
      soltar = resolve;
    });
    let pedidos = 0;
    aoBuscar = async (url) => {
      if (!url.includes('/saldo-diario-consolidado')) return json(200, { linhas: [], total: 0 });
      pedidos += 1;
      if (pedidos === 1) await trava;
      return json(200, saldoDia('9.50', [grupo('Pix', '10.50', '1.00', '9.50')]));
    };
    const fixture = await montar();
    await clicar(fixture, 'menu-consolidado');
    definir(fixture, 'consolidado-inicio', '2026-01-02');
    definir(fixture, 'consolidado-fim', '2026-01-02');
    (fixture.nativeElement.querySelector('#btn-saldo') as HTMLButtonElement).click();
    definir(fixture, 'consolidado-inicio', '2026-01-05');
    definir(fixture, 'consolidado-fim', '2026-01-01');
    (fixture.nativeElement.querySelector('#btn-saldo') as HTMLButtonElement).click();
    fixture.detectChanges();
    soltar();
    await flush(fixture);

    expect(saldos()).toHaveLength(1);
    expect(aviso(fixture, 'aviso-consolidado')).toBe('A data final é anterior à inicial.');
    expect(fixture.nativeElement.querySelector('#total-periodo')).toBeNull();
    expect((fixture.nativeElement.querySelector('#acordeao') as HTMLElement).textContent ?? '').not.toContain('Data 2026-01-02');
  });

  it('pagina seguinte pede a pagina 1', async () => {
    environment.comercianteId = CAIXA;
    aoBuscar = () => json(200, { linhas: [lancamento()], total: 25 });
    const fixture = await montar();
    await clicar(fixture, 'btn-seguinte');
    const url = new URL(chamadas[1]?.url ?? '');

    expect(url.searchParams.get('page')).toBe('1');
    expect(url.searchParams.get('size')).toBe('20');
    expect(lancamentos().every((chamada) => chamada.header === CAIXA)).toBe(true);
  });
});
