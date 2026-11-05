export const TAMANHO_PAGINA = 20;
export const LIMITE_DIAS = 31;
export const DIA_MS = 86400000;

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export const RECUSA: Record<string, readonly [string, string]> = {
  'data invalida': ['data', 'Data'],
  'valor invalido': ['valor', 'Valor'],
  'direcao invalida': ['debito', 'Débito'],
  'tipo_pagamento invalido': ['tipo-registro', 'Tipo de pagamento do lançamento'],
  'descricao ausente': ['descricao', 'Descrição'],
  'comprovante ilegivel': ['comprovante', 'Comprovante'],
};

export interface GrupoSaldo {
  tipo: string;
  creditos: string;
  debitos: string;
  saldo: string;
}

export interface DiaSaldo {
  data: string;
  grupos: GrupoSaldo[];
  saldo: string;
  centavos: number;
}

export interface Linha {
  id: string;
  data: string;
  valor: string;
  direcao: string;
  tipo_pagamento: string;
  descricao: string;
  comprovante: boolean;
}

export interface Pagina {
  linhas: Linha[];
  total: number;
  quantidade: number;
}

export interface PeriodoDias {
  tipo: 'dias';
  datas: string[];
}

export interface PeriodoRecusa {
  tipo: 'recusa';
  frase: string;
}

export interface PeriodoIncompleto {
  tipo: 'incompleto';
}

export type Periodo = PeriodoDias | PeriodoRecusa | PeriodoIncompleto;

export function comercianteValido(id: string): boolean {
  return UUID.test(id);
}

export function valorApi(texto: string): string {
  const limpo = texto.trim().split(',').join('.');
  const casas = /^(0|[1-9]\d*)\.(\d{1,2})$/.exec(limpo);
  if (casas) {
    const inteiro = casas[1] ?? '';
    const fracao = casas[2] ?? '';
    return inteiro + '.' + (fracao.length === 1 ? fracao + '0' : fracao);
  }
  const inteiro = /^(0|[1-9]\d*)$/.exec(limpo);
  if (inteiro) return (inteiro[1] ?? '') + '.00';
  return limpo;
}

export function reais(texto: string): string {
  if (typeof texto !== 'string') return '';
  const encontrado = /^(-?)(0|[1-9]\d*)\.(\d{2})$/.exec(texto);
  if (!encontrado) return '';
  const sinal = encontrado[1] === '-' ? '-' : '';
  return 'R$ ' + sinal + (encontrado[2] ?? '') + ',' + (encontrado[3] ?? '');
}

export function mostrarQuantia(texto: string): string {
  return reais(texto) || texto;
}

export function falaValor(texto: string): string {
  const api = valorApi(texto);
  const encontrado = /^(-?)(0|[1-9]\d*)\.(\d{2})$/.exec(api);
  if (!encontrado) return '';
  const reaisParte = encontrado[2] ?? '';
  const fracaoTexto = encontrado[3] ?? '';
  const nomeReais = reaisParte === '1' ? 'real' : 'reais';
  let centavos = fracaoTexto.charAt(0) === '0' ? fracaoTexto.slice(1) : fracaoTexto;
  if (!centavos) centavos = '0';
  const nomeCentavos = centavos === '1' ? 'centavo' : 'centavos';
  const fala = reaisParte + ' ' + nomeReais + ' e ' + centavos + ' ' + nomeCentavos;
  return encontrado[1] === '-' ? 'menos ' + fala : fala;
}

export function centavosDe(texto: string): number | null {
  if (typeof texto !== 'string') return null;
  const encontrado = /^(-?)(0|[1-9]\d*)\.(\d{2})$/.exec(texto);
  if (!encontrado) return null;
  const reaisParte = digitos(encontrado[2] ?? '');
  const fracao = digitos(encontrado[3] ?? '');
  if (reaisParte === null || fracao === null) return null;
  const centavos = reaisParte * 100 + fracao;
  return encontrado[1] === '-' ? -centavos : centavos;
}

export function textoDeCentavos(centavos: number): string {
  let sinal = '';
  let resto = centavos;
  if (resto < 0) {
    sinal = '-';
    resto = -resto;
  }
  const fracao = resto % 100;
  const reaisParte = (resto - fracao) / 100;
  return sinal + reaisParte + '.' + doisDigitos(fracao);
}

export function centavosVisiveis(dias: readonly DiaSaldo[]): number {
  let soma = 0;
  for (const dia of dias) {
    if (dia.grupos.length === 0) continue;
    soma += dia.centavos;
  }
  return soma;
}

export function instanteUtc(texto: string): number | null {
  const encontrado = /^(\d{4})-(\d{2})-(\d{2})$/.exec(texto);
  if (!encontrado) return null;
  const ano = digitos(encontrado[1] ?? '');
  const mes = digitos(encontrado[2] ?? '');
  const dia = digitos(encontrado[3] ?? '');
  if (ano === null || mes === null || dia === null) return null;
  const data = new Date(Date.UTC(ano, mes - 1, dia));
  if (data.getUTCFullYear() !== ano || data.getUTCMonth() !== mes - 1 || data.getUTCDate() !== dia) return null;
  return data.getTime();
}

export function isoDeUtc(ms: number): string {
  const data = new Date(ms);
  return data.getUTCFullYear() + '-' + doisDigitos(data.getUTCMonth() + 1) + '-' + doisDigitos(data.getUTCDate());
}

export function limitesDoMes(agora: Date): { inicio: string; fim: string } {
  const ano = agora.getFullYear();
  const mes = agora.getMonth();
  return {
    inicio: isoLocal(new Date(ano, mes, 1)),
    fim: isoLocal(new Date(ano, mes + 1, 0)),
  };
}

function isoLocal(data: Date): string {
  return data.getFullYear() + '-' + doisDigitos(data.getMonth() + 1) + '-' + doisDigitos(data.getDate());
}

export function avaliarPeriodo(inicio: string, fim: string): Periodo {
  const a = instanteUtc(inicio);
  const b = instanteUtc(fim);
  if (a === null || b === null) return { tipo: 'incompleto' };
  if (b < a) return { tipo: 'recusa', frase: 'A data final é anterior à inicial.' };
  const quantidade = (b - a) / DIA_MS + 1;
  if (quantidade > LIMITE_DIAS) return { tipo: 'recusa', frase: 'O período passa de 31 dias.' };
  const datas: string[] = [];
  for (let cursor = a; cursor <= b; cursor += DIA_MS) datas.push(isoDeUtc(cursor));
  return { tipo: 'dias', datas };
}

export function diaDoCorpo(data: string, corpo: unknown): DiaSaldo | null {
  if (!corpo || typeof corpo !== 'object') return null;
  const registro = corpo as Record<string, unknown>;
  const saldo = registro['saldo da data'];
  if (!Array.isArray(registro['grupos']) || typeof saldo !== 'string') return null;
  const centavos = centavosDe(saldo);
  if (centavos === null) return null;
  const grupos: GrupoSaldo[] = [];
  for (const item of registro['grupos']) {
    const grupo = grupoDoCorpo(item);
    if (!grupo) return null;
    grupos.push(grupo);
  }
  return { data, grupos, saldo, centavos };
}

export function paginaDe(corpo: unknown): Pagina | null {
  if (!corpo || typeof corpo !== 'object') return null;
  const registro = corpo as Record<string, unknown>;
  if (!Array.isArray(registro['linhas']) || typeof registro['total'] !== 'number') return null;
  const linhas: Linha[] = [];
  for (const item of registro['linhas']) {
    const linha = linhaDe(item);
    if (linha) linhas.push(linha);
  }
  return { linhas, total: registro['total'], quantidade: registro['linhas'].length };
}

export function idAceito(corpo: unknown): string {
  if (!corpo || typeof corpo !== 'object') return '';
  const id = (corpo as Record<string, unknown>)['id'];
  return typeof id === 'string' ? id : '';
}

export function lerProblema(corpo: unknown): { title: string; detail: string } {
  if (!corpo || typeof corpo !== 'object') return { title: '', detail: '' };
  const registro = corpo as Record<string, unknown>;
  return {
    title: typeof registro['title'] === 'string' ? registro['title'] : '',
    detail: typeof registro['detail'] === 'string' ? registro['detail'] : '',
  };
}

export function campoDaRecusa(detail: string): readonly [string, string] {
  return RECUSA[detail] ?? ['valor', 'Valor'];
}

export function detalheInvalidaComerciante(detail: string): boolean {
  return detail.indexOf('X-Comerciante-Id') !== -1;
}

export function fraseRegistroRecusado(nome: string, titulo: string): string {
  return titulo ? 'Registro recusado. ' + nome + '. ' + titulo + '.' : 'Registro recusado. ' + nome + '.';
}

export function fraseConsultaRecusada(titulo: string): string {
  return titulo ? 'Consulta recusada. ' + titulo + '.' : 'Consulta recusada.';
}

export function fraseDaLista(quantidade: number, total: number): string {
  if (quantidade === 0) return 'Nenhum lançamento.';
  const linhas = quantidade === 1 ? '1 linha' : quantidade + ' linhas';
  return linhas + '. Total ' + total + '.';
}

export function fraseAceite(incluindo: boolean, total: number): string {
  const frase = incluindo ? 'Registro aceito.' : 'Alteração aceita.';
  return frase + ' Total ' + total + '.';
}

export function nomeDirecao(direcao: string): string {
  if (direcao === 'debito') return 'Débito';
  if (direcao === 'credito') return 'Crédito';
  return '';
}

export function classeDirecao(direcao: string): string {
  if (direcao === 'debito') return 'palavra-debito';
  if (direcao === 'credito') return 'palavra-credito';
  return '';
}

export function quantiaVisivel(valor: string): string {
  return reais(valor) || valor;
}

export function fraseComprovante(linha: Linha, texto: string): string {
  const valor = quantiaVisivel(linha.valor);
  let frase = 'Comprovante. Data ' + linha.data + '. Tipo de pagamento ' + linha.tipo_pagamento + '. Valor ' + valor + '.';
  if (!texto) frase += ' O arquivo não tem texto.';
  return frase;
}

export function fraseFalhaComprovante(linha: Linha): string {
  return 'Comprovante não abre. ' + quantiaVisivel(linha.valor) + '. ' + linha.descricao;
}

export function textoLegivel(buffer: ArrayBuffer | null): string {
  if (!buffer || buffer.byteLength === 0) return '';
  const bytes = new Uint8Array(buffer);
  for (let i = 0; i < bytes.length; i++) {
    if (bytes[i] === 0) return '';
  }
  try {
    const texto = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    return texto.trim() ? texto : '';
  } catch {
    return '';
  }
}

function grupoDoCorpo(grupo: unknown): GrupoSaldo | null {
  if (!grupo || typeof grupo !== 'object') return null;
  const registro = grupo as Record<string, unknown>;
  if (typeof registro['tipo_pagamento'] !== 'string') return null;
  if (typeof registro['total de créditos'] !== 'string') return null;
  if (typeof registro['total de débitos'] !== 'string') return null;
  if (typeof registro['saldo do tipo'] !== 'string') return null;
  return {
    tipo: registro['tipo_pagamento'],
    creditos: registro['total de créditos'],
    debitos: registro['total de débitos'],
    saldo: registro['saldo do tipo'],
  };
}

function linhaDe(item: unknown): Linha | null {
  if (!item || typeof item !== 'object') return null;
  const registro = item as Record<string, unknown>;
  if (typeof registro['id'] !== 'string') return null;
  return {
    id: registro['id'],
    data: registro['data'] == null ? '' : String(registro['data']),
    valor: typeof registro['valor'] === 'string' ? registro['valor'] : '',
    direcao: typeof registro['direcao'] === 'string' ? registro['direcao'] : '',
    tipo_pagamento: registro['tipo_pagamento'] == null ? '' : String(registro['tipo_pagamento']),
    descricao: registro['descricao'] == null ? '' : String(registro['descricao']),
    comprovante: registro['comprovante'] === true,
  };
}

function doisDigitos(valor: number): string {
  return valor < 10 ? '0' + valor : String(valor);
}

function digitos(texto: string): number | null {
  let valor = 0;
  for (let i = 0; i < texto.length; i++) {
    const codigo = texto.charCodeAt(i);
    if (codigo < 48 || codigo > 57) return null;
    valor = valor * 10 + (codigo - 48);
  }
  return valor;
}
